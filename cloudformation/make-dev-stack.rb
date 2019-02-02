#!/usr/bin/env ruby

require 'yaml'
require 'optimist'
require 'awesome_print'

def types_to_filter(exclude_lambdas)
  types_to_filter_out = [
      "AWS::Elasticsearch::Domain",
      "AWS::ElasticLoadBalancing::LoadBalancer",
      "AWS::IAM::InstanceProfile",
      "AWS::EC2::SecurityGroupIngress",
      "AWS::AutoScaling::LaunchConfiguration",
      "AWS::AutoScaling::AutoScalingGroup"
  ]

  if exclude_lambdas
    types_to_filter_out.concat([
                                 "AWS::EC2::SecurityGroup",
                                 "AWS::Lambda::Permission",
                                 "AWS::IAM::Role",
                                 "AWS::Lambda::Function",
                                 "AWS::Events::Rule"
                             ])
   else
    types_to_filter_out
  end

end

# Filter out any resources that are not needed for the dev stack, based on the Type field
# Internally, this calls `types_to_filter` to get the list of resource types to remove
#
# @param data [Hash] parsed data of the entire Cloudformation document, in a Hash format
# @param exclude_lambdas [Boolean] if true, then also exclude any resources relating to lambda functions
# @return [Hash] an updated copy of the Cloudformation document, with the relevant resources filtered out
def filter_resources(data, exclude_lambdas)
  types_to_filter_out = types_to_filter(exclude_lambdas)

  data.merge({"Resources"=>data['Resources'].select {|name, entry|
    not types_to_filter_out.include?(entry["Type"])
  }})
end

# Gets a list of the names of each resource that will be filtered out, based on the Type field
# Internally, this calls `types_to_filter` to get the list of resource types to remove
#
# @param data [Hash] parsed data of the entire Cloudformation document, in a Hash format
# @param exclude_lambdas [Boolean] if truem then also exclude any resources relating to lambda functions (i.e., include their names in the output list)
# @return [Array] a list of the names of each resource that will be filtered out
def get_filtered_resource_names(data, exclude_lambdas)
  types_to_filter_out = types_to_filter(exclude_lambdas)
  excluded = data["Resources"].select do |name, entry|
    types_to_filter_out.include?(entry["Type"])
  end
  excluded.keys
end

# Remove references in the Outputs to Resources that have been filtered out
#
# @param data [Hash] parsed data of the resource-filtered Cloudformation document, in Hash format
# @param resource_names [Array[String]] list of the resource names that have been filtered out.  Call `get_filtered_resource_names` to generate this.
# @return [Hash] an updated copy of the filtered Cloudformation document, with the relevant Outputs filtered out
def remove_filtered_output_refs(data, resource_names)
  data.merge({"Outputs"=>data["Outputs"].select {|name, entry|
    not resource_names.reduce(false) do |acc,resource_name|
      acc || entry['Value'].include?(resource_name)
    end
  }})
end

# Extract any ${} tokens from a !Sub parameter string and return them as an Array
#
# @param subString [String] body of a !Sub request, i.e. everything from after !Sub to the end of line
# @return Array[String] list of substition token names, i.e. the bit between ${ and }. If none present, then an empty array is returned/
def extract_sub_tokens(subString)
  result = subString.scan(/\${([^}]+)}/)
  if result
    result[0][0]
  else
    []
  end
end

def extract_string_references(entry)
  subStrings = entry.scan(/BangSub\s*(.*)$/)
  subRefs = if subStrings.length>0
    subStrings[0].map do |s| extract_sub_tokens(s) end
  else
    []
  end
  refStrings = entry.scan(/Ref\s*(.*)$/)
  if refStrings and refStrings[0]
    subRefs + refStrings[0]
  else
    subRefs
  end

end

#Recursively find all references within the provided data has.
# A "reference" in this context is the parameter to a !Ref or any tokens within a !Sub
#
# @param data [Hash] parsed data of the Resources section of a Cloudformation document, in Hash format
# @param level [Integer] recursion level, defaults to zero. Don't specify when calling.
# @return Array[String] list of references contained within the
def find_references(data, level=0)
  data.keys.reduce(Array.new) do |acc, name|
    entry = data[name]
    if entry.is_a?(Hash)
      next_level = find_references(entry, level+1)
      if acc && next_level
        acc + next_level
      else
        acc
      end
    elsif entry.is_a?(String)
      acc + extract_string_references(entry)
    elsif entry.is_a?(Array)
      acc + entry.select { |item| item.is_a?(Hash)}.flat_map {|value| find_references(value, level+1)} + \
          entry.select { |item| item.is_a?(String)}.flat_map {|value| extract_string_references(value) }
    else
      acc
    end
  end
end

# Removes anything from the Parameters block that is not referenced elsewhere in the (updated) document
#
# @param data [Hash] parsed data of the entire (filtered) Cloudformation document, in Hash format
# @param references [Array[String]] list of everything that _is_ referenced in the Resources section.  Any value matching one of these is left in; everything else is filtered out.
# @return [Hash] updated Cloudformation document tree, with the Parameters section modified.
def filter_inputs(data, references)
  data.merge({"Parameters"=>data["Parameters"].select {|name, entry|
    references.include?(name)
  }})
end

# The Ruby YAML module seems to drop the "free tags" that are preceded by a ! character.  So, before parsing,
# we convert them into a standard string and after serialization we convert them back with `unescape_exclamations`.
#
# == Parameters:
# string:: a string representation of the entire cloudformation YAML, for processing
#
# == Returns:
# A string with !Sub, !GetAtt and !Fn::* references replaced with BangSub, BangGetAtt and BangFn*
def escape_exclamations(string)
  pass = []
  pass[0]=string.gsub(/\!Sub/,"BangSub")
  pass[1]=pass[0].gsub(/\!GetAtt/,"BangGetAtt")
  pass[2]=pass[1].gsub(/\!Fn::([^:]+)/, "BangFn\1")
  pass[2].gsub(/\!Ref/,"BangRef")
end

# Convert the BangSub, BangGetAtt etc. references back into ! references post-serialization
#
# == Parameters:
# string:: a string representation of the serialized yaml for processing
#
# == Returns:
# A string with BangSub, BangGetAtt and BangFn* references replaced with !Sub, !GetAtt and !Fn::
def unescape_exclamations(string)
  pass = []
  pass[0]=string.gsub(/BangSub/,"!Sub")
  pass[1]=pass[0].gsub(/BangGetAtt/,"!GetAtt")
  pass[2]=pass[1].gsub(/BangFn([^:]+)/, "!Fn::\1")
  pass[2].gsub(/BangRef/, "!Ref")
end

### START MAIN
opts = Optimist::options do
  opt :input, "Name of the full cloudformation to strip down", :default=>"appstack.yaml"
  opt :output, "Name of the file to output the stripped down cloudformation to", :default=>"appstack-dev.yaml"
  opt :excludelambdas, "Also exclude lambda functions and associated resources", :type=>:boolean, :default=>false
end

data = File.open(opts.input) do |f|
  YAML.load(escape_exclamations(f.read))
end

data_pass = []

data_pass[0] = filter_resources(data,opts.excludelambdas)
data_pass[1] = remove_filtered_output_refs(data_pass[0], get_filtered_resource_names(data, opts.excludelambdas))
references = find_references(data_pass[1]["Resources"])
data_pass[2] = filter_inputs(data_pass[1], references)

File.open(opts.output, "wb") do |f|
  f.write(unescape_exclamations(YAML.dump(data_pass[2])))
end

puts "Output written to #{opts.output}"