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
package org.apache.phoenix.mapreduce.util;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import org.apache.hadoop.io.Writable;

public interface ViewInfoWritable extends Writable {
  public enum ViewInfoJobState {
    INITIALIZED(1),
    RUNNING(2),
    SUCCEEDED(3),
    FAILED(4),
    KILLED(5),
    DELETED(6);

    int value;

    ViewInfoJobState(int value) {
      this.value = value;
    }

    public int getValue() {
      return this.value;
    }
  }

  void write(DataOutput output) throws IOException;

  void readFields(DataInput input) throws IOException;

  String getTenantId();

  String getViewName();

  String getRelationName(); // from index or data table

  boolean isIndexRelation();
}
