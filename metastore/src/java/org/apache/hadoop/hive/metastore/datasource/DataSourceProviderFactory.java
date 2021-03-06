/**
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
package org.apache.hadoop.hive.metastore.datasource;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.conf.HiveConf;

/**
 * Create a DataSourceProvider for a connectionPool configured in a hadoop
 * Configuration object.
 */
public abstract  class DataSourceProviderFactory {
  private static final ImmutableList<DataSourceProvider> FACTORIES = ImmutableList.of(
      new HikariCPDataSourceProvider(),
      new BoneCPDataSourceProvider(),
      new DbCPDataSourceProvider());

  /**
   * The data source providers declare if they are supported or not based on the config.
   * This function looks through all the data source providers and picks the first one which is
   * supported. If no data source provider is found, returns a null.
   *
   * @param hdpConfig hadoop configuration
   * @return factory for the configured datanucleus.connectionPoolingType or null if no supported
   *         data source providers are found.
   */
  public static DataSourceProvider tryGetDataSourceProviderOrNull(Configuration hdpConfig) {
    final String configuredPoolingType =
        HiveConf.getVar(hdpConfig, HiveConf.ConfVars.METASTORE_CONNECTION_POOLING_TYPE);
    return Iterables.tryFind(FACTORIES, factory -> {
      String poolingType = factory.getPoolingType();
      return poolingType != null && poolingType.equalsIgnoreCase(configuredPoolingType);
    }).orNull();
  }

  /**
   * @param hdpConfig hadoop configuration
   * @return true if the configuration contains settings specifically aimed for one
   * of the supported connection pool implementations.
   */
  public static boolean hasProviderSpecificConfigurations(Configuration hdpConfig) {
    String poolingType = HiveConf.getVar(hdpConfig,
        HiveConf.ConfVars.METASTORE_CONNECTION_POOLING_TYPE).toLowerCase();
    return Iterables.any(hdpConfig, entry ->
    {
      String key = entry.getKey();
      return key != null && (key.startsWith(poolingType));
    });
  }
}
