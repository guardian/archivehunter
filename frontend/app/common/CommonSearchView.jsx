import React from "react";
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import SearchResultsComponent from '../search/SearchResultsComponent.jsx';
import axios from "axios";
import {handle419} from "./Handle419.jsx";

/**
 * this class abstracts functions that are common to all the search/browse views
 */
class CommonSearchView extends React.Component {
    constructor(props){
        super(props);

        this.onItemClose = this.onItemClose.bind(this);
        this.onItemOpen = this.onItemOpen.bind(this);
    }

    onItemOpen(newTarget){
        this.setState({showingPreview: newTarget})
    }

    onItemClose(){
        this.setState({showingPreview: null});
    }

    indexForFileid(entryId){
        for(var i=0;i<this.state.searchResults.length;++i){
            console.debug("checking "+this.state.searchResults[i].id+ "against" + entryId);
            if(this.state.searchResults[i].id===entryId) return i;
        }
        console.error("Could not find existing entry for id " + entryId);
        return -1;
    }

    /**
     * update the `searchResults` state array by replacing the item at `entryIndex` with the data of `newEntry`.
     * It's assumed that `entryIndex` has been retrieved from indexForFileid().
     * if the `showingPreview` state variable is pointing to `entryId` then it to is updated to point to `newEntry`.
     * @param newEntry new data to put
     * @param entryIndex
     * @param entryId
     * @returns {Promise}
     */
    updateSearchResults(newEntry, entryIndex, entryId){
        return new Promise((resolve, reject)=> {
            const updatedSearchResults =
                this.state.searchResults.slice(0, entryIndex).concat([newEntry].concat(this.state.searchResults.slice(entryIndex + 1)));
            this.setState({searchResults: updatedSearchResults}, () => {
                if (this.state.showingPreview.id === entryId){
                    console.log("update completed");
                    this.setState({showingPreview: newEntry}, ()=>resolve());
                } else {
                    console.log("update completed");
                    resolve();
                }
            });
        });
    }

    /**
     * updates the search view data for a specific item once it has been added to the lightbox.
     * Returns a Promise that resolves once the operation is fully completed
     * @param entryId entry to update
     * @returns {Promise} Promise that resolves once all updates have been done
     */
    addedToLightbox(entryId){
        return new Promise((resolve, reject)=> {
            //we have to use a small delay, otherwise the server returns stale data (that the item is NOT in the lightbox yet)
            window.setTimeout(() => {
                this.setState({loading: true}, () => axios.get("/api/entry/" + entryId).then(response => {
                    const entryIndex = this.indexForFileid(entryId);
                    console.info("got existing entry at " + entryIndex);

                    if (entryIndex >= 0) {
                        this.updateSearchResults(response.data.entry, entryIndex, entryId).then(()=>resolve());
                    } else {
                        this.setState({searchResults: this.state.searchResults.concat([response.data.entry])}, ()=>resolve());
                    }
                }).catch(err=>{
                    console.error(err);
                    handle419(err).then(didRefresh=>{
                        if(didRefresh){
                            this.addedToLightbox(entryId);
                        }
                    })
                }))
            }, 1000);
        });
    }

    renderMainBody(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        } else if(this.state.totalHits!==-1){
            return <SearchResultsComponent entries={this.state.searchResults} onItemOpen={this.onItemOpen} onItemClose={this.onItemClose} selectedEntry={this.state.showingPreview}/>
        } else if(this.state.searching) {
            return <img style={{marginLeft:"auto",marginRight:"auto",width:"200px",display:"block"}} src="/assets/images/Spinner-1s-200px.gif"/>
        } else {
            return <span/>
        }
    }
}

export default CommonSearchView;