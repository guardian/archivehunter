import {reEstablishSession} from "panda-session";
import axios from 'axios';

const reAuthUrl = "/loginAuthStub";

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
        //console.log(err);
        if (err && err.response && err.response.status === 419) {
            console.log("Received 419 error indicating expired credentials");
            reEstablishSession(reAuthUrl,5000).then(result=>{   //need a timeout
                console.log("Re-established session");
                resolve(true);
            }).catch(err=>{
                console.log("Could not re-establish session", err);
                reject("Could not re-establish session");
            })
        } else {
            resolve(false);
        }
    });
}

function rejectedCallback(err){
    const originalRequest = err.config;
    return new Promise((resolve,reject)=>{
        handle419(err).then(didRefresh=>{
            if(didRefresh){
                axios.request(originalRequest).then(result=>resolve(result)).catch(err=>reject(err));
            } else {
                reject(err);
            }
        }).catch(err=>{
            if(err==="Could not re-establish session"){
                console.log("Would normally reload now");
                window.location.reload(true);   //forcing a reload should get the user to log in again, if we couldn't re-establish
                resolve("reloading");   //this should not get called in the browser, but is necessary for testing.
            } else {
                reject(err);
            }
        })
    });
}

function setupInterceptor(){
    /**
     * Axios interceptor function to handle 419 credential timeout errors.
     * Any axios error will get filtered through the callback here, which will check if the error is a 419
     * and attempt to refresh the session if so.
     * If we could not do an automatic refresh, the page is reloaded; otherwise the error is passed back to the caller
     */
    axios.interceptors.response.use(null, rejectedCallback);
}

export {handle419, setupInterceptor, rejectedCallback};