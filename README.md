# README (work in progress)

# Setting up the development environment

It's expected that you'll be developing on this on your local machine; but since the app relies on many
AWS resources in order to function (DynamoDB, SQS, etc.) you'll need some of these that you can mess around with and not
break anything else.

### 1. Generate dev stack Cloudformation

There is a helper script provided that takes the main app cloudformation and strips out everything you don't need for a
 dev stack:
 
```
[checkout-root] $ cd cloudformation
[checkout-root]/cloudformation $ ./make-dev-stack.rb -e
Output written to appstack-dev.yaml
```

In order to run this script, you'll need ruby 2.x installed together with the
Optimist gem: `gem install optimist`

You can override the input and output filenames, run `./make-dev-stack.rb --help` for details.

Once it has been generated, **deploy** the cloudformation in your usual way (probably via the AWS console)

### 2. Generate dev stack configuration

Once you have your dev stack configured, you need to update `application.conf` to point to the newly deployed resources.

There is a script provided to help with this as well:

```
[checkout-root] $ cd cloudformation
[checkout-root]/cloudformation $ ./make-dev-config.rb -s {your-stack-name} [-r {region}]
[checkout-root]/cloudformation $
```

This requires the aws-sdk gem as well as Optimist:

```
$ gem install aws-sdk optimist
```

This will generate a new file in the `conf/` directory called `application-DEV.conf` by replacing the relevant
configuration keys with values obtained from the stack `{your-stack-name}`.  In order to run, this requires AWS credentials
with the rights to inspect deployed Cloudformation stacks.

The exact replacements are controlled in a table at the top of the script called `$settings_mapping`.  Consult this if the output is not what you expect.

Run `$ ./make-dev-config.rb --help` for more information on available options.

In order to use the configuration, you need to specify `config.file=application-DEV.conf` when running.
In Intellij, this can be done by going to the Run configuration and specifying `-Dconfig.file=application-DEV.conf` in the JVM Options box.
From the commandline it should be sufficient to add the -D option as above to your commandline.
### 3. Set up Panda

Archive hunter uses the Pan-Domain Authentication system (aka Panda) for authentication.

This is a method of securely sharing a Google loging between different apps deployed onto the same DNS domain (see https://github.com/guardian/pan-domain-authentication for details).

If you already have a Panda domain set up, then you can simply point the app to your config bucket and deployment domain
by adjusting the relevant keys  in `application-DEV.conf`.  Ensure that your development credentials grant you access to
your Panda config bucket.

If you don't have one set up, here is the quick guide to configuring it.  For details and troubleshooting refer to the 
link above.

- create an account at Google Cloud Console.
- create an app, then create a set of Oauth credentials.  Download these.
- create an S3 bucket to hold the configuration. This does need to host public files too.
- create a file called `{your-domain}.settings` and populate it as per https://github.com/guardian/pan-domain-authentication#setting-up-your-domain-configuration
- create a file called `{your-domain}.settings.public` and put the public key ONLY into it as per https://github.com/guardian/pan-domain-authentication#setting-up-your-domain-configuration.
- upload the settings files to your bucket. The public one should have public read access, the main settings file should have no external access
- when deploying appstack.yaml, one of the configuration parameters is the name for the bucket.  The app is given read access to the whole bucket, so it can perform the auth.
- when developing, update `application-DEV.conf` to contain the (local) deployment domain and the name of the settings bucket. Your development credentials will need to be able to read the private settings file for the app to work.

**However** this is not enough.  Google auth (and Panda) requires that the app is hosted on HTTPS, even in development. A reverse proxy is required.

### 4. Set up reverse proxy (dev-nginx)

The Guardian provides a sample nginx configuration to make setting up the https reverse proxy simple.  Go and grab https://github.com/guardian/dev-nginx and follow the instructions there.

The config for ArchiveHunter is in `nginx-mapping.yaml` in the root of the sources.

If you're not developing within the Guardian you'll have to make some modifications to the nginx configurations.

### 5. Copy in data (optional)

A script is provided at `utils/copy_dynamo_table.rb` so you can copy the contents of e.g. your CODE environment proxies, jobs, etc. into
your DEV tables in order to debug and fix issues.

This requires the following gems:

```
$ gem install optimist aws-sdk awesome_print
```

Run `utils/copy_dynamo_table.rb --help` to see the usage instructions.


-----------------

## Notification lambdas
While periodic scanning is all very well, it's usually a better idea to pick up incoming events as they happen rather than bundle them into large infrequent updates.
That is where the inputLambda event code comes in handy.

To use this:

- deploy `cloudformation/bucketmonitor.yaml`, ensuring that permissions are granted for access to any buckets you want to monitor
- manually configure each bucket to send events to the lambda:
  - go to the bucket in S3 console
  - click 'Properties'
  - under 'Advanced', select 'Events'
  - within 'Events' select 'New Notification'
  - select 'ObjectCreate(all)' and 'ObjectDelete(all)'
  - give it a name
  - select the lambda that the cloudformation `bucketmonitor.yaml` deployed in 'Send To'
  
Once this has been done, the index will be automatically updated with any new or deleted files without a need for scanning