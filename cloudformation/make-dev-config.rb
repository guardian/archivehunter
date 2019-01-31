#!/usr/bin/env ruby

require 'optimist'
require 'aws-sdk'
require 'awesome_print'

# Proc that converts an SQS URL (obtained from cloudformation) into the corresponding ARN.
# This is a proc rather than a method, so that it can be referenced from the $settings_mapping table below.
#
# @param qurl [String] the URL to convert
# @param region [String] the region that the ARN should be scoped to
# @return [String] the ARN that corresponds to the provided URL, or raises a StandardError
arn_from_queue_url = ->(qurl, region) {
  matches = qurl.scan(/sqs.eu-west-1.amazonaws.com\/(\d+)\/([^\/]+)/)
  if matches && matches.length >0
    "arn:aws:sqs:#{region}:#{matches[0][0]}:#{matches[0][1]}"
  else
    raise StandardError, "#{qurl} does not look like an SQS URL"
  end
}

# This hash is an association table for the HOCON path on the right to the "logical" name in the cloudformation on the left.
# When the script is run, the terms identified on the right are replaced with the corresponding "physical" id of the resource identified on the right.
# If the right operand (value) is an Array, then it is expected to contain exactly two elements; the first being a Proc that accepts the
# Cloudformation value as its first parameter and the region as its second, and the second element being the logical ID of the Cloudformation
# resource to apply.
# NOTE: this is not a proper HOCON parser. At present, only a single (optional) section name and key name are supported.
$settings_mapping = {
    "externalData.scanTargets" => "ScanTargetsTable",
    "externalData.jobTable" => "JobHistoryTable",
    "auth.userProfileTable" => "UserProfileTable",
    "proxyFramework.notificationsQueue" => "ProxyFrameworkMsg",
    "proxyFramework.notificationsQueueArn" => [arn_from_queue_url, "ProxyFrameworkMsg"],
    "proxyFramework.trackingTable" => "ProxyFrameworkTable",
    "proxies.tableName"=> "ProxyLocationTable",
    "ingest.notificationsQueue" => "IngestTranscodeMsg",
    "lightbox.tableName" => "LightboxTable"
}


# Builds a Hash of all the resources associated with the given stack, in the form of logical_id=>physical_id
#
# @param stack_name [String] stack to interrogate
# @param client [Aws::CloudFormation::Client] initialized cloudformation client object
# @return [Hash] hash of resources in the given stack
def interrogate_stack(stack_name, client)
  def get_next_page(client, stack_name, current_items,  next_token)
    puts("DEBUG: getting stack #{stack_name}")
    result = client.list_stack_resources({:stack_name=>stack_name,:next_token=>next_token})
    updated_items = current_items.merge(result.stack_resource_summaries.map {|entry| [entry.logical_resource_id, entry.physical_resource_id]}.to_h)
    if result.next_token
      get_next_page(client, stack_name, updated_items, next_token)
    else
      updated_items
    end
  end

  get_next_page(client, stack_name, {}, nil)
end

def maybe_replace_line(line, current_section, stack_data, region)
  matches = line.scan(/^(\s*)([^=\s]+)\s*=\s*"(.*)".*$/)
  if matches.length>0
    mapping_key = if current_section
                    "#{current_section}.#{matches[0][1]}"
                  else
                    matches[0][1]
                  end
    #puts "mapping key is #{mapping_key}"
    if $settings_mapping.has_key?(mapping_key)
      key = $settings_mapping[mapping_key]
      if key.is_a?(Array)
        proc = key[0]
        phys_id = stack_data[key[1]]
        "#{matches[0][0]}#{matches[0][1]} = \"#{proc.(phys_id, region)}\"\n"
      else
        value = stack_data[key]
        "#{matches[0][0]}#{matches[0][1]} = \"#{value}\"\n"
      end
    else
      line
    end
  else
    line
  end
end

### START MAIN
opts = Optimist::options do
  opt :stack, "Name of deployed dev stack to interrogate", :type=>:string
  opt :region, "Region that the stack is in", :default=>"eu-west-1", :type=>:string
  opt :output, "Name of the config to output", :default=>"../conf/application-DEV.conf", :type=>:string
end

client = Aws::CloudFormation::Client.new(:region=>opts.region)
stack_data = interrogate_stack(opts[:stack], client)

current_section = nil
File.open(opts.output,"wb") do |out|
  File::foreach("../conf/application.conf") do |line|
    section_match = line.scan(/^\s*(\w+)\s*{/)
    current_section = if section_match && section_match.length>0
      section_match[0][0]
    else
      current_section
    end
    out.write(maybe_replace_line(line, current_section, stack_data, opts.region))
  end
end
