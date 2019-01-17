#!/usr/bin/env ruby

require 'trollop'
require 'aws-sdk'
require 'awesome_print'
require 'json'

opts = Trollop::options do
  opt :from, "Table to copy from", :type=>:string
  opt :to, "Table to copy to", :type=>:string
  opt :region, "Region to work in", :type=>:string, :default=>"eu-west-1"
  opt :tempfile, "temp filename to use", :type=>:string
  opt :batchsize, "Batch size for writing", :type=>:int, :default=>25
end


def recursively_get_next_page(client, tableName, lastEvaluatedKey, pageNumber, existingResults=nil)
  result = client.scan(:table_name=>tableName,:exclusive_start_key=>lastEvaluatedKey)
  puts "get_next_page got #{result.items.length} #{result.count} items"

  if(existingResults)
    updatedResults = existingResults.concat result.items
  else
    updatedResults = result.items
  end

  if result.last_evaluated_key
    recursively_get_next_page(client, tableName, result.last_evaluated_key, pageNumber+1, updatedResults)
  else
    updatedResults
  end
end

def batch_write(client, tableName, resultSlice)
  commandList = resultSlice.map{ |entry| {put_request: {item: entry}}}

  result = client.batch_write_item(:request_items=>{tableName=>commandList})
  puts "batch_write wrote #{resultSlice.length} entries to #{tableName}"
end

##START MAIN
client = Aws::DynamoDB::Client.new(:region=>opts.region)

tempfilename = "/tmp/" + opts.from + "_" + opts.to + ".json"

results = recursively_get_next_page(client, opts.from, nil, 0)

results.each_slice(opts.batchsize) do |slice|
  batch_write(client, opts.to, slice)
end

File.open(tempfilename,"wb") do |f|
  f.write(JSON.generate(results))
end
