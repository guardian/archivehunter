import React from "react";
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import SearchResultsComponent from '../search/SearchResultsComponent.jsx';
import axios from "axios";

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

    addedToLightbox(entryId){
        window.setTimeout(()=> {
            this.setState({loading: true}, () => axios.get("/api/entry/" + entryId).then(response => {
                const entryIndex = this.indexForFileid(entryId);
                console.info("got existing entry at " + entryIndex);

                if (entryIndex >= 0) {
                    const updatedSearchResults =
                        this.state.searchResults.slice(0, entryIndex).concat([response.data.entry].concat(this.state.searchResults.slice(entryIndex + 1)));
                    this.setState({searchResults: updatedSearchResults}, () => {
                        if (this.state.showingPreview.id === entryId) this.setState({showingPreview: response.data.entry});
                        console.log("update completed")
                    });
                } else {
                    this.setState({searchResults: this.state.searchResults.concat([response.data.entry])})
                }
            }))
        }, 250);
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