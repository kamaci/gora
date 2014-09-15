/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.gora.goraci;

import org.apache.gora.goraci.generated.CINode;
import org.apache.gora.goraci.generated.Flushed;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.avro.util.Utf8;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.gora.mapreduce.GoraMapper;
import org.apache.gora.query.Query;
import org.apache.gora.query.Result;
import org.apache.gora.store.DataStore;
import org.apache.gora.store.DataStoreFactory;
import org.apache.gora.util.GoraException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.VLongWritable;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

/**
 * A Map Reduce job that verifies that the linked list generated by {@link org.apache.gora.goraci.Generator} do not have any holes.
 */
public class Verify extends Configured implements Tool {
  
  private static final Log LOG = LogFactory.getLog(Verify.class);
  private static final VLongWritable DEF = new VLongWritable(-1);
  
  private Job job;
  

  public static class VerifyMapper extends GoraMapper<Long,CINode,LongWritable,VLongWritable> {
    private LongWritable row = new LongWritable();
    private LongWritable ref = new LongWritable();
    private VLongWritable vrow = new VLongWritable();
    private Map<Utf8,Long> flushed = null;
    
    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      super.setup(context);
      
      String[] entries = context.getConfiguration().getStrings("org.apache.gora.goraci.verify.flushed");
      
      if (entries != null && entries.length > 0) {
        flushed = new HashMap<Utf8,Long>();
        for (String entry : entries) {
          String[] kv = entry.split(":");
          flushed.put(new Utf8(kv[0]), Long.parseLong(kv[1]));
        }
      }
    }
    
    @Override
    protected void map(Long key, CINode node, Context context) throws IOException, InterruptedException {
      if (flushed != null) {
        Long count = flushed.get(node.getClient());
        if (count == null || node.getCount() >= count) {
          context.getCounter(Counts.IGNORED).increment(1);
          return;
        }
      }

      row.set(key);
      context.write(row, DEF);
      
      if (node.getPrev() >= 0) {
        ref.set(node.getPrev());
        vrow.set(key);
        context.write(ref, vrow);
      }
    }
  }

  public static enum Counts {
    UNREFERENCED, UNDEFINED, REFERENCED, CORRUPT, IGNORED
  }
  
  public static class VerifyReducer extends Reducer<LongWritable,VLongWritable,Text,Text> {
    private ArrayList<Long> refs = new ArrayList<Long>();
    
    public void reduce(LongWritable key, Iterable<VLongWritable> values, Context context) throws IOException, InterruptedException {
      
      int defCount = 0;
      
      refs.clear();
      for (VLongWritable type : values) {
        if (type.get() == -1) {
          defCount++;
        } else {
          refs.add(type.get());
        }
      }
      
      // TODO check for more than one def, should not happen

      if (defCount == 0 && refs.size() > 0) {
        // this is bad, found a node that is referenced but not defined. It must have been lost, emit some info about this node for debugging purposes.
        
        StringBuilder sb = new StringBuilder();
        String comma = "";
        for (Long ref : refs) {
          sb.append(comma);
          comma = ",";
          sb.append(String.format("%016x", ref));
        }
        
        context.write(new Text(String.format("%016x", key.get())), new Text(sb.toString()));
        context.getCounter(Counts.UNDEFINED).increment(1);
        
      } else if (defCount > 0 && refs.size() == 0) {
        // node is defined but not referenced
        context.getCounter(Counts.UNREFERENCED).increment(1);
      } else {
        // node is defined and referenced
        context.getCounter(Counts.REFERENCED).increment(1);
      }
      
    }
  }
 
  @Override
  public int run(String[] args) throws Exception {
    
    Options options = new Options();
    options.addOption("c", "concurrent", false, "run concurrently with generation");
    
    GnuParser parser = new GnuParser();
    CommandLine cmd = null;
    try {
      cmd = parser.parse(options, args);
      if (cmd.getArgs().length != 2) {
        throw new ParseException("Did not see expected # of arguments, saw " + cmd.getArgs().length);
      }
    } catch (ParseException e) {
      System.err.println("Failed to parse command line " + e.getMessage());
      System.err.println();
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(getClass().getSimpleName() + " <output dir> <num reducers>", options);
      System.exit(-1);
    }

    String outputDir = cmd.getArgs()[0];
    int numReducers = Integer.parseInt(cmd.getArgs()[1]);

    return run(outputDir, numReducers, cmd.hasOption("c"));
  }

  public int run(String outputDir, int numReducers, boolean concurrent) throws Exception {
    return run(new Path(outputDir), numReducers, concurrent);
  }
  
  public int run(Path outputDir, int numReducers, boolean concurrent) throws Exception {
    start(outputDir, numReducers, concurrent);
    
    boolean success = job.waitForCompletion(true);
    
    return success ? 0 : 1;
  }
  
  public void start(Path outputDir, int numReducers, boolean concurrent) throws GoraException, IOException, Exception {
    LOG.info("Running Verify with outputDir=" + outputDir +", numReducers=" + numReducers);
    
    DataStore<Long,CINode> store = DataStoreFactory.getDataStore(Long.class, CINode.class, new Configuration());

    job = new Job(getConf());
    
    if (!job.getConfiguration().get("io.serializations").contains("org.apache.hadoop.io.serializer.JavaSerialization")) {
      job.getConfiguration().set("io.serializations", job.getConfiguration().get("io.serializations") + ",org.apache.hadoop.io.serializer.JavaSerialization");
    }

    job.setJobName("Link Verifier");
    job.setNumReduceTasks(numReducers);
    job.setJarByClass(getClass());
    
    Query<Long,CINode> query = store.newQuery();
    if (!concurrent) {
      // no concurrency filtering, only need prev field
      query.setFields("prev");
    } else {
      readFlushed(job.getConfiguration());
    }

    GoraMapper.initMapperJob(job, query, store, LongWritable.class, VLongWritable.class, VerifyMapper.class, true);

    job.getConfiguration().setBoolean("mapred.map.tasks.speculative.execution", false);
    
    job.setReducerClass(VerifyReducer.class);
    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, outputDir);

    store.close();
    
    job.submit();
  }
  
  public boolean isComplete() throws IOException {
    return job.isComplete();
  }

  public boolean isSuccessful() throws IOException {
    return job.isSuccessful();
  }
  
  public boolean waitForCompletion() throws IOException, InterruptedException, ClassNotFoundException {
    return job.waitForCompletion(true);
  }

  private void readFlushed(Configuration conf) throws Exception {
    DataStore<Utf8,Flushed> flushedTable = DataStoreFactory.getDataStore(Utf8.class, Flushed.class, conf);
    
    Query<Utf8,Flushed> query = flushedTable.newQuery();
    Result<Utf8,Flushed> result = flushedTable.execute(query);
    
    ArrayList<String> flushedEntries = new ArrayList<String>();
    while (result.next()) {
      flushedEntries.add(result.getKey() + ":" + result.get().getCount());
    }
    
    conf.setStrings("org.apache.gora.goraci.verify.flushed", flushedEntries.toArray(new String[] {}));
    
    flushedTable.close();
  }

  public boolean verify(long expectedReferenced) throws Exception {
    if (job == null) {
      throw new IllegalStateException("You should call run() first");
    }
    
    Counters counters = job.getCounters();
    
    Counter referenced = counters.findCounter(Counts.REFERENCED);
    Counter unreferenced = counters.findCounter(Counts.UNREFERENCED);
    Counter undefined = counters.findCounter(Counts.UNDEFINED);
    
    boolean success = true;
    //assert
    if (expectedReferenced != referenced.getValue()) {
      LOG.error("Expected referenced count does not match with actual referenced count. " +
      		"expected referenced=" + expectedReferenced + " ,actual=" + referenced.getValue());
      success = false;
    }

    if (unreferenced.getValue() > 0) { 
      LOG.error("Unreferenced nodes were not expected. Unreferenced count=" + unreferenced.getValue());
      success = false;
    }
    
    if (undefined.getValue() > 0) { 
      LOG.error("Found an undefined node. Undefined count=" + undefined.getValue());
      success = false;
    }
    
    return success;
  }
  
  public static void main(String[] args) throws Exception {
    int ret = ToolRunner.run(new Verify(), args);
    System.exit(ret);
  }
}