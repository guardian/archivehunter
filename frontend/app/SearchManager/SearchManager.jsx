import Searcher from './Searcher.jsx';

class SearchManager {
    constructor(){
        this.currentSearch = null;

    }

    /**
     * create a new search. Stop the existing one before kicking this one off, if so.
     * @param method
     * @param url
     * @param params
     * @param bodyContent
     * @param pageSize
     * @param nextPageCb
     * @param completedCb
     * @param cancelledCb
     * @param errorCb
     */
    makeNewSearch(method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb){
        const searchId = this.generateId();
        if(this.currentSearch){
            this.currentSearch.cancel("new search terms").then(result=>{
                this.currentSearch = new Searcher(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb);
                this.currentSearch.startSearch();
            })
        } else {
            this.currentSearch = new Searcher(searchId, method, url, params, bodyContent, pageSize, nextPageCb, completedCb, cancelledCb, errorCb);
            this.currentSearch.startSearch();
        }

        return searchId;
    }


}

export default SearchManager;