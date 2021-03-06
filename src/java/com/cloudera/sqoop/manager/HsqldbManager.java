/**
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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

package com.cloudera.sqoop.manager;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.cloudera.sqoop.SqoopOptions;

/**
 * Manages connections to hsqldb databases.
 * Extends generic SQL manager.
 */
public class HsqldbManager extends GenericJdbcManager {

  public static final Log LOG = LogFactory.getLog(
      HsqldbManager.class.getName());

  // driver class to ensure is loaded when making db connection.
  private static final String DRIVER_CLASS = "org.hsqldb.jdbcDriver";

  // HsqlDb doesn't have a notion of multiple "databases"; the user's database
  // is always called "PUBLIC".
  private static final String HSQL_SCHEMA_NAME = "PUBLIC";

  public HsqldbManager(final SqoopOptions opts) {
    super(DRIVER_CLASS, opts);
  }

  /**
   * Return list of databases hosted by the server.
   * HSQLDB only supports a single schema named "PUBLIC".
   */
  @Override
  public String[] listDatabases() {
    String [] databases = {HSQL_SCHEMA_NAME};
    return databases;
  }

  @Override
  /**
   * {@inheritDoc}
   */
  protected String getCurTimestampQuery() {
    // HSQLDB requires that you select from a table; this table is
    // guaranteed to exist.
    return "SELECT CURRENT_TIMESTAMP FROM INFORMATION_SCHEMA.SYSTEM_TABLES";
  }

  @Override
  public boolean supportsStagingForExport() {
    return true;
  }
}
