# README (work in progress)

# Setting up the development environment

It's expected that you'll be developing on this on your local machine; but since the app relies on many
AWS resources in order to function (DynamoDB, SQS, etc.) you'll need some of these that you can mess around with and not
break anything else.

### 0. Before you begin
You'll need the following prerequisites installed on your dev environment:

- ruby 2.x for helper scripts, with `aws-sdk optimist awesome_print` gems
- AWS commandline utilities (`pip install awscli` helps)
- AWS commandline access.  We use temporary credentials based on AWS commandline profiles; set the `externalData.awsProfile` option
in your settings file (see step 2) to the profile name you are using on the commandline if you need this
- SBT or similar Scala build environment
- nodejs v6 or above with npm installed. I use `nvm` to manage my nodejs versions, and v10 for development.

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

#### Option 1
The Guardian provides a sample nginx configuration to make setting up the https reverse proxy simple.  Go and grab https://github.com/guardian/dev-nginx and follow the instructions there.

The config for ArchiveHunter is in `nginx-mapping.yaml` in the root of the sources.

If you're not developing within the Guardian you'll have to make some modifications to the nginx configurations.

#### Option 2 (simpler)
We have had issues with some Macs refusing to run Homebrew or Macports, and with old SSL versions that cause a multitude of
problems trying to install nginx.

Fortunately docker provides a solution, which is available in the source tree at `utils/revproxy`.

- Before you start, you must check on the "external" ip address that Docker can communicate back to your machine with.  This _should_ be as simple as setting it to "docker.for.mac.localhost" (without the quotes); or "docker.for.win.localhost" on Windows.  However, you may have to fire up a container and run something like this:
```
docker run --rm -it nginx:alpine /bin/sh
/ # route
Kernel IP routing table
Destination     Gateway         Genmask         Flags Metric Ref    Use Iface
default         172.17.0.1      0.0.0.0         UG    0      0        0 eth0
172.17.0.0      *               255.255.0.0     U     0      0        0 eth0
/ #
```

- Edit the file `utils/revproxy/server.conf` and update the `proxy_pass` line to point to either `docker.for.mac.localhost` or this IP address.
- Then, set up both `server_name` parameters to the "local" domain you're using for testing.
   - This hostname must ultimately resolve to `localhost`.
- Finally, add the certificates for the domain name you specified (.crt and .key) to the `utils/revproxy/certs` directory,
and add the filenames to the `ssl_certificate` and `ssl_certificate_key` entries, including the `certs/` prefix.
   - Generating self-signed certificates is out of the scope of this documentation, but there is plenty of information
   available on the web how to do this on nginx.
   - the `certs/` directory is bound to the `/etc/nginx/certs` directory in the container
   - Gitignore is set up to not include .crt or .key files.

Now you're ready to run it: `cd utils/revproxy; ./revproxy.sh`.  At the time of writing the script depends on being in that directory
in order to resolve mount paths for Docker.

With it running, you should be able to start up the app in Intellij (serving to port 9000) and access it in a browser at 
https://{your-local-domain-name}.

Remember that Intellij or sbt will compile everything _after first access_, so you may have to wait a while until you see something in the browser.
Check the compilation console to be sure that the message got through

**Troubleshooting**

If you get "**502 Server not available**" when trying to access https://{your-local-domain-name}, it means that nginx is not getting
a response from port 9000 on the local host. Check that the app is running, and that is is correctly binding to the port.

If you get "**504 Server timeout**" then the host IP address is likely incorrect.  Re-do the instructions at the top of this section
to find out the correct IP address

### 5. Copy in data (optional)

A script is provided at `utils/copy_dynamo_table.rb` so you can copy the contents of e.g. your CODE environment proxies, jobs, etc. into
your DEV tables in order to debug and fix issues.

This requires the following gems:

```
$ gem install optimist aws-sdk awesome_print
```

Run `utils/copy_dynamo_table.rb --help` to see the usage instructions.

### 6. Run local Elasticsearch cluster

Fortunately, Docker makes this very easy. Just run `utils/run_local_elasticsearch.sh`.  This will start up a single-node dev
cluster in Docker, storing its data in a subdirectory called `esdata` which is included in the .gitignore file.

### 7. Build the UI in development mode

The UI is provided by nodejs, and needs transpiling from JSX:

```
[checkout-root] $ cd frontend/
[checkout-root]/frontend $ npm install
[checkout-root]/frontend $ npm run dev
```
 
This will build the file `[checkout-root]/public/javascripts/bundle.js` with all of the UI in it and will keep running, rebuilding
it every time the contents of the JSX files change.

---------

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
