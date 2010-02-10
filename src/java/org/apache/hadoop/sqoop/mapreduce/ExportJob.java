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

package org.apache.hadoop.sqoop.mapreduce;

import java.io.FileNotFoundException;
import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.db.DBConfiguration;
import org.apache.hadoop.mapreduce.lib.db.DBOutputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.SequenceFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;

import org.apache.hadoop.sqoop.ConnFactory;
import org.apache.hadoop.sqoop.SqoopOptions;
import org.apache.hadoop.sqoop.lib.SqoopRecord;
import org.apache.hadoop.sqoop.manager.ConnManager;
import org.apache.hadoop.sqoop.manager.ExportJobContext;
import org.apache.hadoop.sqoop.orm.TableClassName;
import org.apache.hadoop.sqoop.util.ClassLoaderStack;
import org.apache.hadoop.sqoop.util.ExportException;

/**
 * Actually runs a jdbc export job using the ORM files generated by the sqoop.orm package.
 * Uses DBOutputFormat
 */
public class ExportJob {

  public static final Log LOG = LogFactory.getLog(ExportJob.class.getName());

  public static final String SQOOP_EXPORT_TABLE_CLASS_KEY = "sqoop.export.table.class";

  private ExportJobContext context;

  public ExportJob(final ExportJobContext ctxt) {
    this.context = ctxt;
  }

  /**
   * Run an export job to dump a table from HDFS to a database
   * @throws IOException if the export job encounters an IO error
   * @throws ExportException if the job fails unexpectedly or is misconfigured.
   */
  public void runExport() throws ExportException, IOException {

    SqoopOptions options = context.getOptions();
    Configuration conf = options.getConf();
    String tableName = context.getTableName();
    String tableClassName = new TableClassName(options).getClassForTable(tableName);
    String ormJarFile = context.getJarFile();

    LOG.info("Beginning export of " + tableName);

    boolean isLocal = "local".equals(conf.get("mapreduce.jobtracker.address"))
        || "local".equals(conf.get("mapred.job.tracker"));
    ClassLoader prevClassLoader = null;
    if (isLocal) {
      // If we're using the LocalJobRunner, then instead of using the compiled jar file
      // as the job source, we're running in the current thread. Push on another classloader
      // that loads from that jar in addition to everything currently on the classpath.
      prevClassLoader = ClassLoaderStack.addJarFile(ormJarFile, tableClassName);
    }

    try {
      Job job = new Job(conf);

      // Set the external jar to use for the job.
      job.getConfiguration().set("mapred.jar", ormJarFile);

      Path inputPath = new Path(context.getOptions().getExportDir());
      inputPath = inputPath.makeQualified(FileSystem.get(conf));

      boolean isSeqFiles = ExportInputFormat.isSequenceFiles(
          context.getOptions().getConf(), inputPath);

      if (isSeqFiles) {
        job.setMapperClass(SequenceFileExportMapper.class);
      } else {
        job.setMapperClass(TextExportMapper.class);
      }

      job.setInputFormatClass(ExportInputFormat.class);
      FileInputFormat.addInputPath(job, inputPath);
      job.setNumReduceTasks(0);
      ExportInputFormat.setNumMapTasks(job, options.getNumMappers());

      // Concurrent writes of the same records would be problematic.
      job.setMapSpeculativeExecution(false);

      ConnManager mgr = new ConnFactory(conf).getManager(options);
      String username = options.getUsername();
      if (null == username || username.length() == 0) {
        DBConfiguration.configureDB(job.getConfiguration(), mgr.getDriverClass(),
            options.getConnectString());
      } else {
        DBConfiguration.configureDB(job.getConfiguration(), mgr.getDriverClass(),
            options.getConnectString(), username, options.getPassword());
      }

      String [] colNames = options.getColumns();
      if (null == colNames) {
        colNames = mgr.getColumnNames(tableName);
      }
      DBOutputFormat.setOutput(job, tableName, colNames);

      job.setOutputFormatClass(DBOutputFormat.class);
      job.getConfiguration().set(SQOOP_EXPORT_TABLE_CLASS_KEY, tableClassName);
      job.setMapOutputKeyClass(SqoopRecord.class);
      job.setMapOutputValueClass(NullWritable.class);

      try {
        boolean success = job.waitForCompletion(false);
        if (!success) {
          throw new ExportException("Export job failed!");
        }
      } catch (InterruptedException ie) {
        throw new IOException(ie);
      } catch (ClassNotFoundException cnfe) {
        throw new IOException(cnfe);
      }
    } finally {
      if (isLocal && null != prevClassLoader) {
        // unload the special classloader for this jar.
        ClassLoaderStack.setCurrentClassLoader(prevClassLoader);
      }
    }
  }
}
