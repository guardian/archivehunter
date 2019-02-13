#!/usr/bin/env ruby

require 'elasticsearch'
require 'optimist'
require 'hashie'
require 'json'
require 'logger'

opts = Optimist::options do
  opt :es, "Elasticsearch URL", :type=>:string, :default=>"http://localhost:9200"
  opt :debug, "debug mode", :type=>:boolean, :default=>false
  opt :indexname, "Index name", :type=>:string, :default=>"archivehunter"
  opt :pagesize, "Page size", :type=>:integer, :default=>1000
end

Hashie.logger.level = Logger::ERROR

def write_page(results, filenamebase, page_num)
  data = JSON.generate(results.hits.hits)
  filename = filenamebase + page_num.to_s.rjust(4, "0") + ".json"
  puts "Writing page to #{filename}"
  open(filename,"w") do |f|
    f.write(data)
  end
end

def get_next_page(client, indexname, start_at, page_size, scroll_id)
  puts "Reading page of #{page_size} from #{start_at} with #{scroll_id}"
  if scroll_id==nil
    results = client.search({:index=>indexname, :body=>{query: { match_all: {}}},:scroll=>"10m",:size=>page_size})
  else
    results = client.scroll({:scroll=>"10m",:body=>{:scroll_id=>scroll_id}})
  end

  Hashie::Mash.new results
end

client = Elasticsearch::Client.new url: opts.es, log: opts.debug

ctr = 0
scroll_id = nil
while true
  results_page = get_next_page(client, opts.indexname, ctr*opts.pagesize, opts.pagesize, scroll_id)
  pct = (ctr*opts.pagesize*100)/results_page.hits.total
  puts "Got #{results_page.hits.hits.length} results ( #{ctr*opts.pagesize} / #{results_page.hits.total}; #{pct}%)"
  break if results_page.hits.hits.length==0
  scroll_id = results_page._scroll_id if results_page._scroll_id

  write_page(results_page, "#{opts.indexname}-", ctr)
  ctr+=1
end

