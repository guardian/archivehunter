import Searcher from './Searcher.jsx';
import uuid from 'uuid/v4';

class SearchManager {
    constructor(){
        this.currentSearch = null;

    }

    generateId(){
        return uuid();
    }

    /**
     * internal method to build the search object. Included like this for testing.
     * @param searchId
     * @param method
     * @param url
     * @param params
     * @param bodyContent
     * @param pageSize
     * @param nextPageCb
     * @param completedCb
     * @param cancelledCb
     * @param errorCb
     * @private
     */
    _buildSearch(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb){
        return new Searcher(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb);
    }

    /**
     * create a new search. Stop the existing one before kicking this one off, if so.
     * returns a Promise that resolves with the new search ID, once it is running.
     * @param method
     * @param url
     * @param params
     * @param bodyContent - either object containing {data, contentType} keys or null
     * @param pageSize
     * @param nextPageCb
     * @param completedCb
     * @param cancelledCb
     * @param errorCb
     */
    makeNewSearch(method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb) {
        return new Promise((resolve, reject) => {
            const searchId = this.generateId();
            if (this.currentSearch) {
                console.log("Cancelling current search - makeNewSearch has been called");
                this.currentSearch.cancel("new search terms").then(result => {
                    console.log("Cancellation prcessed, building new one...");
                    this.currentSearch = this._buildSearch(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb);
                    this.currentSearch.startSearch();
                    resolve(this.currentSearch.searchId);
                })
            } else {
                this.currentSearch = this._buildSearch(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb);
                this.currentSearch.startSearch();
                resolve(this.currentSearch.searchId);
            }
        });
    }

    resumeSearch() {
        if(this.currentSearch) {
            this.currentSearch.getNextPage();
        } else {
            console.error("Cannot resume, no currentSearch");
        }
    }

}

export default SearchManager;