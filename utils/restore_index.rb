#!/usr/bin/env ruby

require 'elasticsearch'
require 'json'
require 'optimist'
require 'awesome_print'

opts = Optimist::options do
  opt :es, "Elasticsearch URL", :type=>:string, :default=>"http://localhost:9200"
  opt :debug, "debug mode", :type=>:boolean, :default=>false
  opt :indexname, "Index name", :type=>:string, :default=>"archivehunter"
  opt :destindex, "Destination index if different from source", :type=>:string
  opt :force, "Remove any existing index before we start", :type=>:boolean, :default=>false
  opt :path, "Look for data files in this path", :type=>:string, :default=>"."
end

def find_data_files(filenamebase, path)
  matcher = /#{filenamebase}\-(\d+).json$/
  ap matcher

  Dir.entries(path).select do |filename|
    matcher.match(filename)
  end
end

def load_data(client, filename, destindex)
  open(filename,"rb") do |f|
    data = JSON.parse(f.read)
    actions = data.map { |entry|
      {
          :index=>{
              :_index=>destindex ? destindex: entry["_index"],
              :_id=>entry["_id"],
              :_type=>entry["_type"],
              :data=>entry["_source"]
          }
      }
    }
    client.bulk body: actions
  end
end


### START MAIN
client = Elasticsearch::Client.new url: opts.es, log: opts.debug
data_file_list = find_data_files(opts.indexname, opts.path)

if data_file_list.length==0
  puts("ERROR: Could not find any data files in #{opts.path} matching #{filenamebase}-nnnn.json")
  exit(1)
end

data_file_list.each do |file|
  load_data(client, file, opts.destindex)
end