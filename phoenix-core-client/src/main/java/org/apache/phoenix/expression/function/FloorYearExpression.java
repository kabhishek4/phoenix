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
package org.apache.phoenix.expression.function;

import java.util.List;
import org.apache.phoenix.expression.Expression;
import org.joda.time.DateTime;
import org.joda.time.chrono.GJChronology;

/**
 * Floor function that rounds up the {@link DateTime} to start of year.
 */
public class FloorYearExpression extends RoundJodaDateExpression {

  public FloorYearExpression() {
    super();
  }

  public FloorYearExpression(List<Expression> children) {
    super(children);
  }

  @Override
  public long roundDateTime(DateTime datetime) {
    return datetime.year().roundFloorCopy().getMillis();
  }

  @Override
  public long rangeLower(long time) {
    // floor
    return roundDateTime(new DateTime(time, GJChronology.getInstanceUTC()));
  }

  @Override
  public long rangeUpper(long time) {
    // ceil(time + 1) -1
    return (new DateTime(time + 1, GJChronology.getInstanceUTC())).year().roundCeilingCopy()
      .getMillis() - 1;
  }
}
