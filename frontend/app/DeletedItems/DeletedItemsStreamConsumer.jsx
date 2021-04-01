import ndjsonStream from "can-ndjson-stream";

/**
 * consumes the NDJSON stream of deleted objects
 * @param collection collection name to scan
 * @param prefix optional path-prefix to search within
 * @param searchDoc an AdvancedSearchDocument
 * @param callback callback to receive data.  This is called once for every row with the arguments (ArchiveEntry, isDone boolean).
 * To continue the loading operation, it should return true; to cancel an in-progress operation it should return false.
 * @param delay optional time to wait between loads, in milliseconds. Can help prevent the page jamming/stuttering because javascript
 * renders are taking up all the runtime
 * @returns {Promise<void>}
 */
async function loadDeletedItemStream(collection, prefix, searchDoc, callback, delay) {
    let args = "";
    if(prefix) {
        args = `?prefix=${encodeURIComponent(prefix)}`
    }

    const response = await fetch(`/api/deleted/${collection}/search${args}`,{
        method: "PUT",
        body: JSON.stringify(searchDoc),
        headers: {
            "Content-Type": "application/json"
        }
    });

    const asyncDelay = (ms) => {
        return new Promise((resolve, reject)=>window.setTimeout(()=>resolve, ms))
    }

    switch(response.status) {
        case 200:
            const streamReader = ndjsonStream(response.body).getReader();
            let result;
            while(!result || !result.done) {
                result = await streamReader.read();
                const shouldContinue = callback(result.value, result.done);
                if(!shouldContinue) {
                    await streamReader.cancel("User cancelled loading");
                    return;
                }
                if(delay) await asyncDelay(delay);
            }
            break;
        case 502|503|504:
            console.warn("Server is not responding, retrying shortly...");
            return new Promise((resolve, reject)=>{
                window.setTimeout(()=>{
                    loadDeletedItemStream(collection, prefix, searchDoc, callback)
                        .then(resolve)
                        .catch(reject)
                }, 2000);
            });
        default:
            console.error("Server returned an unexpected code: ", response.status);
            return;
    }
}

export {loadDeletedItemStream};