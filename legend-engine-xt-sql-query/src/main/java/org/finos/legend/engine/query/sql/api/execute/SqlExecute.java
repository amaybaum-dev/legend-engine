// Copyright 2022 Goldman Sachs
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.finos.legend.engine.query.sql.api.execute;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.opentracing.Scope;
import io.opentracing.util.GlobalTracer;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.eclipse.collections.api.RichIterable;
import org.eclipse.collections.api.block.function.Function;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.list.MutableList;
import org.eclipse.collections.impl.factory.Maps;
import org.eclipse.collections.impl.utility.ArrayIterate;
import org.eclipse.collections.impl.utility.LazyIterate;
import org.finos.legend.engine.language.pure.compiler.toPureGraph.PureModel;
import org.finos.legend.engine.language.pure.modelManager.ModelManager;
import org.finos.legend.engine.language.pure.modelManager.sdlc.configuration.MetaDataServerConfiguration;
import org.finos.legend.engine.language.sql.grammar.from.SQLGrammarParser;
import org.finos.legend.engine.plan.execution.PlanExecutor;
import org.finos.legend.engine.plan.execution.result.Result;
import org.finos.legend.engine.plan.execution.result.serialization.SerializationFormat;
import org.finos.legend.engine.plan.execution.service.ServiceModeling;
import org.finos.legend.engine.plan.generation.transformers.PlanTransformer;
import org.finos.legend.engine.protocol.pure.PureClientVersions;
import org.finos.legend.engine.protocol.pure.v1.model.context.PureModelContextData;
import org.finos.legend.engine.protocol.pure.v1.model.executionPlan.SingleExecutionPlan;
import org.finos.legend.engine.protocol.pure.v1.model.packageableElement.service.Service;
import org.finos.legend.engine.protocol.sql.metamodel.Node;
import org.finos.legend.engine.protocol.sql.metamodel.Query;
import org.finos.legend.engine.protocol.sql.metamodel.Translator;
import org.finos.legend.engine.shared.core.ObjectMapperFactory;
import org.finos.legend.engine.shared.core.deployment.DeploymentMode;
import org.finos.legend.engine.shared.core.kerberos.HttpClientBuilder;
import org.finos.legend.engine.shared.core.kerberos.ProfileManagerHelper;
import org.finos.legend.engine.shared.core.operational.errorManagement.EngineException;
import org.finos.legend.engine.shared.core.operational.logs.LogInfo;
import org.finos.legend.engine.shared.core.operational.logs.LoggingEventType;
import org.finos.legend.engine.shared.core.operational.prometheus.MetricsHandler;
import org.finos.legend.pure.generated.Root_meta_external_query_sql_Schema;
import org.finos.legend.pure.generated.Root_meta_external_query_sql_metamodel_Node;
import org.finos.legend.pure.generated.Root_meta_external_query_sql_transformation_queryToPure_SQLSource;
import org.finos.legend.pure.generated.Root_meta_external_query_sql_transformation_queryToPure_SQLSource_Impl;
import org.finos.legend.pure.generated.Root_meta_legend_service_metamodel_PureSingleExecution;
import org.finos.legend.pure.generated.Root_meta_legend_service_metamodel_Service;
import org.finos.legend.pure.generated.Root_meta_pure_executionPlan_ExecutionPlan;
import org.finos.legend.pure.generated.Root_meta_pure_extension_Extension;
import org.finos.legend.pure.generated.core_external_format_json_toJSON;
import org.finos.legend.pure.generated.core_external_query_sql_binding_fromPure_fromPure;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.jax.rs.annotations.Pac4JProfileManager;
import org.slf4j.Logger;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import static org.finos.legend.engine.plan.execution.api.result.ResultManager.manageResult;
import static org.finos.legend.engine.plan.generation.PlanGenerator.transformExecutionPlan;

@Api(tags = "SQL - Execution")
@Path("sql/v1/execution")
@Produces(MediaType.APPLICATION_JSON)
public class SqlExecute
{

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Alloy Execution Server - SQL");
    private static final SQLGrammarParser parser = SQLGrammarParser.newInstance();
    private final ModelManager modelManager;
    private final PlanExecutor planExecutor;
    private final Function<PureModel, RichIterable<? extends Root_meta_pure_extension_Extension>> extensions;
    private final MutableList<PlanTransformer> transformers;
    private final MetaDataServerConfiguration metadataServer;
    private final ServiceModeling serviceModeling;
    private final ObjectMapper mapper = ObjectMapperFactory.getNewStandardObjectMapperWithPureProtocolExtensionSupports();


    public SqlExecute(ModelManager modelManager, PlanExecutor planExecutor,
                      Function<PureModel, RichIterable<? extends Root_meta_pure_extension_Extension>> extensions,
                      MutableList<PlanTransformer> transformers, MetaDataServerConfiguration metadataServer,
                      DeploymentMode deploymentMode)
    {
        this.modelManager = modelManager;
        this.planExecutor = planExecutor;
        this.extensions = extensions;
        this.transformers = transformers;
        this.metadataServer = metadataServer;
        this.serviceModeling = new ServiceModeling(modelManager, deploymentMode, planExecutor);
    }

    @POST
    @ApiOperation(value = "Execute a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("executeQueryString/{projectId}")
    @Consumes({MediaType.TEXT_PLAIN})
    public Response executeSql(@Context HttpServletRequest request, @PathParam("projectId") String projectId, String sql, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        SingleExecutionPlan singleExecutionPlan = generateQueryPlan(request, projectId, sql, profiles);
        long start = System.currentTimeMillis();
        return this.execImpl(planExecutor, profiles, request.getRemoteUser(), SerializationFormat.defaultFormat, start, singleExecutionPlan);
    }

    @POST
    @ApiOperation(value = "Execute a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("executeQuery/{projectId}")
    @Consumes({MediaType.APPLICATION_JSON})
    public Response executeSql(@Context HttpServletRequest request, @PathParam("projectId") String projectId, Query query, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        SingleExecutionPlan singleExecutionPlan = generateQueryPlan(request, projectId, query, profiles);
        long start = System.currentTimeMillis();
        return this.execImpl(planExecutor, profiles, request.getRemoteUser(), SerializationFormat.defaultFormat, start, singleExecutionPlan);
    }

    @POST
    @ApiOperation(value = "Generate plans for a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("generatePlanQueryString/{projectId}")
    @Consumes({MediaType.TEXT_PLAIN})
    public Response generatePlan(@Context HttpServletRequest request, @PathParam("projectId") String projectId, String sql, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        SingleExecutionPlan singleExecutionPlan = generateQueryPlan(request, projectId, sql, profiles);
        return Response.ok().type(MediaType.APPLICATION_JSON_TYPE).entity(singleExecutionPlan).build();
    }

    @POST
    @ApiOperation(value = "Generate plans for a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("generatePlanQuery/{projectId}")
    @Consumes({MediaType.APPLICATION_JSON})
    public Response generatePlan(@Context HttpServletRequest request, @PathParam("projectId") String projectId, Query query, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        SingleExecutionPlan singleExecutionPlan = generateQueryPlan(request, projectId, query, profiles);
        return Response.ok().type(MediaType.APPLICATION_JSON_TYPE).entity(singleExecutionPlan).build();
    }

    @POST
    @ApiOperation(value = "Get schema for a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("getSchemaFromQueryString/{projectId}")
    @Consumes({MediaType.TEXT_PLAIN})
    public Response getSchema(@Context HttpServletRequest request, @PathParam("projectId") String projectId, String sql, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        String schema = getSchema(request, projectId, sql, profiles);
        return Response.ok().type(MediaType.APPLICATION_JSON_TYPE).entity(schema).build();
    }

    @POST
    @ApiOperation(value = "Get schema for a SQL query in the context of a Mapping and a Runtime from a SDLC project")
    @Path("getSchemaFromQuery/{projectId}")
    @Consumes({MediaType.APPLICATION_JSON})
    public Response getSchema(@Context HttpServletRequest request, @PathParam("projectId") String projectId, Query query, @ApiParam(hidden = true) @Pac4JProfileManager ProfileManager<CommonProfile> pm, @Context UriInfo uriInfo) throws Exception
    {
        MutableList<CommonProfile> profiles = ProfileManagerHelper.extractProfiles(pm);

        String schema = getSchema(request, projectId, query, profiles);
        return Response.ok().type(MediaType.APPLICATION_JSON_TYPE).entity(schema).build();
    }

    private SingleExecutionPlan generateQueryPlan(HttpServletRequest request, String projectId, String sql, MutableList<CommonProfile> profiles) throws PrivilegedActionException
    {
        Node node = parser.parseStatement(sql);
        return generateQueryPlan(request, projectId, node, profiles);
    }


    private SingleExecutionPlan generateQueryPlan(HttpServletRequest request, String projectId, Node node, MutableList<CommonProfile> profiles) throws PrivilegedActionException
    {
        PureModelContextData pureModelContextData = loadModelContextData(profiles, request, projectId);
        String clientVersion = PureClientVersions.production;
        PureModel pureModel = this.modelManager.loadModel(pureModelContextData, clientVersion, profiles, "");
        Root_meta_external_query_sql_metamodel_Node query = new Translator().translate(node, pureModel);
        RichIterable<? extends Root_meta_external_query_sql_transformation_queryToPure_SQLSource> sources = getSQLSources(pureModelContextData, pureModel);

        Root_meta_pure_executionPlan_ExecutionPlan plan = core_external_query_sql_binding_fromPure_fromPure.Root_meta_external_query_sql_transformation_queryToPure_getPlansFromSQL_SQLSource_MANY__Node_1__Extension_MANY__ExecutionPlan_1_(sources, query, extensions.apply(pureModel), pureModel.getExecutionSupport());
        return transformExecutionPlan(plan, pureModel, clientVersion, profiles, extensions.apply(pureModel), transformers);
    }


    private String getSchema(HttpServletRequest request, String projectId, String sql, MutableList<CommonProfile> profiles) throws PrivilegedActionException
    {
        Node node = parser.parseStatement(sql);
        return getSchema(request, projectId, node, profiles);
    }

    private String getSchema(HttpServletRequest request, String projectId, Node node, MutableList<CommonProfile> profiles) throws PrivilegedActionException
    {
        PureModelContextData pureModelContextData = loadModelContextData(profiles, request, projectId);
        String clientVersion = PureClientVersions.production;
        PureModel pureModel = this.modelManager.loadModel(pureModelContextData, clientVersion, profiles, "");
        Root_meta_external_query_sql_metamodel_Node query = new Translator().translate(node, pureModel);
        RichIterable<? extends Root_meta_external_query_sql_transformation_queryToPure_SQLSource> sources = getSQLSources(pureModelContextData, pureModel);

        Root_meta_external_query_sql_Schema schema = core_external_query_sql_binding_fromPure_fromPure.Root_meta_external_query_sql_transformation_queryToPure_getSchemaFromSQL_SQLSource_MANY__Node_1__Extension_MANY__Schema_1_(sources, query, extensions.apply(pureModel), pureModel.getExecutionSupport());
        return serializeToJSON(schema, pureModel);
    }

    private RichIterable<? extends Root_meta_external_query_sql_transformation_queryToPure_SQLSource> getSQLSources(PureModelContextData pureModelContextData, PureModel pureModel)
    {
        MutableList<Root_meta_legend_service_metamodel_Service> services = LazyIterate.select(pureModelContextData.getElements(), e -> e instanceof Service)
                .collect(e -> (Service) e)
                .collect(e -> serviceModeling.compileService(e, pureModel.getContext(e)))
                .toList();

        return toSources(services);
    }

    private RichIterable<? extends Root_meta_external_query_sql_transformation_queryToPure_SQLSource> toSources(MutableList<Root_meta_legend_service_metamodel_Service> services)
    {
        return services.collect(this::toSource);
    }

    private Root_meta_external_query_sql_transformation_queryToPure_SQLSource toSource(Root_meta_legend_service_metamodel_Service s)
    {
        Root_meta_legend_service_metamodel_PureSingleExecution execution = (Root_meta_legend_service_metamodel_PureSingleExecution) s._execution();
        return new Root_meta_external_query_sql_transformation_queryToPure_SQLSource_Impl("")
                ._type("service")
                ._id(s._pattern())
                ._func(execution._func())
                ._mapping(execution._mapping())
                ._runtime(execution._runtime())
                ._executionOptions(execution._executionOptions());
    }

    protected PureModelContextData loadModelContextData(MutableList<CommonProfile> profiles, HttpServletRequest request, String project) throws PrivilegedActionException
    {
        Subject subject = ProfileManagerHelper.extractSubject(profiles);
        return subject == null ?
                getPureModelContextData(request, project) :
                Subject.doAs(subject, (PrivilegedExceptionAction<PureModelContextData>) () -> getPureModelContextData(request, project));
    }

    private PureModelContextData getPureModelContextData(HttpServletRequest request, String project)
    {
        CookieStore cookieStore = new BasicCookieStore();
        ArrayIterate.forEach(request.getCookies(), c -> cookieStore.addCookie(new MyCookie(c)));


        try (CloseableHttpClient client = (CloseableHttpClient) HttpClientBuilder.getHttpClient(cookieStore))
        {
            if (metadataServer == null || metadataServer.getSdlc() == null)
            {
                throw new EngineException("Please specify the metadataServer.sdlc information in the server configuration");
            }
            HttpGet req = new HttpGet("http://" + metadataServer.getSdlc().host + ":" + metadataServer.getSdlc().port + "/api/projects/" + project + "/pureModelContextData");
            try (CloseableHttpResponse res = client.execute(req))
            {
                return mapper.readValue(res.getEntity().getContent(), PureModelContextData.class);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private Response execImpl(PlanExecutor planExecutor, MutableList<CommonProfile> pm, String user, SerializationFormat format, long start, SingleExecutionPlan plan)
    {
        Result result = planExecutor.execute(plan, Maps.mutable.empty(), user, pm);
        LOGGER.info(new LogInfo(pm, LoggingEventType.EXECUTE_INTERACTIVE_STOP, (double) System.currentTimeMillis() - start).toString());
        MetricsHandler.observe("execute", start, System.currentTimeMillis());
        try (Scope scope = GlobalTracer.get().buildSpan("Manage Results").startActive(true))
        {
            return manageResult(pm, result, format, LoggingEventType.EXECUTE_INTERACTIVE_ERROR);
        }
    }

    static String serializeToJSON(Object pureObject, PureModel pureModel)
    {
        return core_external_format_json_toJSON.Root_meta_json_toJSON_Any_MANY__Integer_$0_1$__Config_1__String_1_(
                Lists.mutable.with(pureObject),
                1000L,
                core_external_format_json_toJSON.Root_meta_json_config_Boolean_1__Boolean_1__Boolean_1__Boolean_1__Config_1_(true, false, false, false, pureModel.getExecutionSupport()),
                pureModel.getExecutionSupport()
        );
    }

}
