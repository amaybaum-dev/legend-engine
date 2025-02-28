// Copyright 2021 Goldman Sachs
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

package org.finos.legend.engine.external.format.flatdata.shared.driver.spi;

import org.finos.legend.engine.plan.dependencies.domain.dataQuality.BasicChecked;
import org.finos.legend.engine.plan.dependencies.domain.dataQuality.IChecked;

public interface ParsedFlatDataToObject<T>
{
    T make(ParsedFlatData parsedFlatData);

    default IChecked<T> makeChecked(ParsedFlatData parsedFlatData)
    {
        return BasicChecked.newChecked(make(parsedFlatData), parsedFlatData);
    }

    default boolean isReturnable()
    {
        return true;
    }

    default void finished()
    {
    }
}
