/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.expression.aggregator;

import java.util.List;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.schema.SortOrder;

/**
 * Client side Aggregator for STDDEV_POP aggregations for DECIMAL data type.
 * @since 1.2.1
 */
public class DecimalStddevPopAggregator extends BaseDecimalStddevAggregator {

  public DecimalStddevPopAggregator(List<Expression> exps, SortOrder sortOrder) {
    super(exps, sortOrder);
  }

  @Override
  protected long getDataPointsCount() {
    return totalCount;
  }
}
