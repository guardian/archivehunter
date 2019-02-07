import {reEstablishSession} from "panda-session";

const reAuthUrl = "/api/loginStatus";

/**
 * call this from all Axios error handlers, passing the error object in.
 * If the error is a 419 (credentials expired), it will attempt to refresh the session and resolve the promise with a true value;
 * if the session refresh failed the value will be false.
 * @param err
 * @returns a Promise containing two boolean values - the first indicating whether we attempted to refresh the session, the second
 * indicating whether the attempt succeeded or failed.
 */
function handle419(err){
    return new Promise((resolve,reject)=> {
        if (err.response.status === 419) {
            console.log("Received 419 error indicating expired credentials");
            reEstablishSession(reAuthUrl).then(result=>{
                console.log("Re-established session");
                resolve(true);
            }).catch(err=>{
                console.log("Could not re-establish session");
                reject(err);
            })
        } else {
            resolve(false, false);
        }
    });
}

export {handle419};