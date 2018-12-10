import axios from 'axios';

class Searcher {
    /**
     * create a new Searcher instance
     * @param searchId GUID for this search instance
     * @param method whether to perform GET, PUT, POST etc. to perform the search
     * @param url url to contact when performing the search
     * @param params any URL parameters to be added to the url. This takes the form of an object with key/value pairs.
     * @param bodyContent text to send as the request body, or undefined/null if not required
     * @param pageSize number of entries to return in a page
     * @param nextPageCb callback for when a new page is returned. Receives the axios response object.
     * @param completedCb callback for when the search has completed (zero results got returned). Receives no arguments
     * @param cancelledCb callback for when the search has been cancelled.
     * @param errorCb callback for when an error has occurred. Receives the axios error object
     */
    constructor(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb){
        this.searchId = searchId;
        this.method = method;
        this.url = url;
        this.params = params;
        this.bodyContent = bodyContent;
        this.nextPageCb = nextPageCb;
        this.completedCb = completedCb;
        this.cancelledCb = cancelledCb;
        this.errorCb = errorCb;
        this.pageSize=pageSize;

        this.operationInProgress = false;
        this.isCancelling = false;
        this.terminationCompletedCb = null;
        this.startAt=0;

        this.cancelTokenSource = axios.CancelToken.source();

        console.log("Searcher body content: ", bodyContent);
    }

    /**
     * cancel this search. Returns a promise that completes once the cancellation has completed
     */
    cancel(msg){
        return new Promise((resolve, reject)=>{
            if(!this.operationInProgress){
                this.terminationCompletedCb = resolve;
                this.isCancelling = true;
                this.cancelTokenSource.cancel(msg); //this is picked up in axios.isCancel() within getNextPage() - the promise is then completed.
            } else {
                this.isCancelling = true;
                resolve();
            }
        })
    }

    /**
     * internal method. called to request the next page of results from the server
     */
    getNextPage(){
        if(this.isCancelling) return;
        this.operationInProgress = true;
        const urlParams = this.params ? Object.assign({size: this.pageSize, startAt:this.startAt}, this.params) : {size: this.pageSize, startAt:this.startAt};
        const urlParamsString = Object.keys(urlParams)
            .map((k,idx)=>encodeURIComponent(k) + "=" + encodeURIComponent(urlParams[k]))
            .join("&");

        const staticHeaders = {};
        const updateHeaders = this.bodyContent && this.bodyContent.contentType ? Object.assign(staticHeaders, {"Content-Type": this.bodyContent.contentType}) : staticHeaders;

        console.log("getNextPage: headers ", updateHeaders);
        console.log("getNextPage: body content ", this.bodyContent ? this.bodyContent.data : null);

        axios.request({
            method: this.method,
            url: this.url + "?" + urlParamsString,
            data: this.bodyContent ? this.bodyContent.data : null,
            headers: updateHeaders
        }).then(response=>{
            if(response.data.entries.length===0){
                this.completedCb(this.searchId);
                this.operationInProgress = false;
            } else {
                const shouldContinue = this.nextPageCb(response, this.searchId);
                this.startAt+=this.pageSize;
                if(shouldContinue) this.getNextPage();
            }
        }).catch(err=>{
            this.operationInProgress = false;
            if(axios.isCancel(err)){
                if(this.cancelledCb) this.cancelledCb(this.searchId);
                if(this.terminationCompletedCb){
                    this.terminationCompletedCb();  //completes the Promise that we return on a cancel() call.
                    this.terminationCompletedCb=null;
                }
            } else {
                this.errorCb(err, this.searchId);
            }
        })
    }

    /**
     * public method. called to start the retrieval of results.
     */
    startSearch(){
        this.startAt=0;
        this.getNextPage();
    }
}

export default Searcher;