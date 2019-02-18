#!/usr/bin/env ruby

require 'aws-sdk'
require 'optimist'
require 'awesome_print'
require 'json'

# looks up the URL for the given  queue_name.
def lookup_queue(client, queue_name)
  raise StandardError, "No queue name to search for!" if queue_name==nil

  results = client.list_queues(queue_name_prefix: queue_name)
  if results.queue_urls.length==0
    raise StandardError, "No queue found for #{queue_name}"
  elsif results.length > 1
    puts("WARNING: found #{results.length} queues for #{queue_name}:")
    results.queue_urls.each { |entry| puts("\t#{entry}") }
  end
  results.queue_urls[0]
end

def get_next_page(client, queue_url, page_size)
  msgList = []

  while msgList.length < page_size
    msgs = client.receive_message({
      queue_url: queue_url,
      max_number_of_messages: 10
    }).messages
    break if msgs.length==0
    msgList.concat(msgs)
  end

  #ap msgList
  msgList
end

#convert the MEssage objects in the list to Hashes for bulk send
def convert_message_list(msgList)
  msgList.map do |entry|
    {
        id: entry.message_id,
        message_body: entry.body,
        message_attributes: entry.message_attributes
    }
  end
end

def parse_body(msg)
  data = JSON.parse(msg.body)
  JSON.parse(data["Message"])
end

def filter_unparseable(msg_list)
  msg_list.select do |entry|
    begin
      parse_body(entry)
      true
    rescue JSON::ParserError
      puts("WARNING: Unparseable message found: #{entry.body}")
      false
    end
  end
end

# push the list of messages to the source queue_url, and delete each one as it is successfully pushed
def push_to_source(client, queue_url, source_queue_url, msgList, keep=false)
  # SQS supports only bulks of up to 10 messages

  msgList.each_slice(10) do |msgSubList|
    results = client.send_message_batch({
                        queue_url: queue_url,
                        entries: convert_message_list(msgSubList)
                                        })
    puts("#{results.successful.length} messages sent, #{results.failed.length} messages failed")
    successful_ids = results.successful.map { |entry| entry.id }
    puts("debug: successful_ids: #{successful_ids}")
    successful_messages = msgList.select { |entry| successful_ids.include?(entry.message_id)}
    #puts("debug: successful_messages: #{successful_messages}")

    successful_messages.each { |entry|
      puts("debug: deleting message #{entry.message_id}; #{entry.receipt_handle} from #{source_queue_url}")
      client.delete_message({queue_url: source_queue_url, receipt_handle: entry.receipt_handle})
    }
  end
end

### START MAIN
opts = Optimist::options do
  opt :dlq, "name of dead-letter queue to read", :type=>:string
  opt :target, "target queue to write to", :type=>:string
  opt :limit, "limit to this number of messages", :type=>:integer, :default=>999999
  opt :pagesize,"shift this many messages at a time", :type=>:integer, :default=>30
  opt :region, "work in this region", :type=>:string, :default=>"eu-west-1"
  opt :keep, "don't delete messages from the DLQ after copying. Intended for use in testing.", :type=>:boolean, :default=>false
end

client = Aws::SQS::Client.new({:region=>opts.region})

dlq_url = lookup_queue(client, opts.dlq)
target_url = lookup_queue(client, opts.target)

ctr=0
while ctr<=opts.limit
  puts("Getting up to #{opts.pagesize} messages...")
  msgList = filter_unparseable(get_next_page(client, dlq_url, opts.pagesize))

  puts("Received #{msgList.length} messages")
  ctr+=msgList.length
  break if msgList.length==0
  push_to_source(client,target_url, dlq_url, msgList, keep: opts.keep)
end

puts("Relayed #{ctr} messages with a limit of #{opts.limit}")