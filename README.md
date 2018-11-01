README (work in progress)

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