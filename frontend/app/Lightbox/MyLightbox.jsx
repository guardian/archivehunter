import React from 'react';
import axios from 'axios';
import EntryDetails from "../Entry/EntryDetails.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import CommonSearchView from "../common/CommonSearchView.jsx";

class MyLightbox extends CommonSearchView {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            searchResults: [],
            userDetails: null,
            showingPreview: null
        }

    }

    performLoad(){
        const detailsRequest = axios.get("/api/lightbox/my/details");
        const summaryRequest = axios.get("/api/search/myLightBox");
        const loginDetailsRequest = axios.get("/api/loginStatus");
        return Promise.all([detailsRequest, summaryRequest, loginDetailsRequest]);
    }

    refreshData(){
        this.setState({loading: true}, ()=>this.performLoad().then(results=>{
            const detailsResult = results[0];   //this is a map of fileId->record
            const summaryResult = results[1];   //this is an array
            const loginDetailsResult = results[2];

            console.log("detailsResult: ", detailsResult);
            console.log("summaryResult: ", summaryResult);

            this.setState({loading: false,
                lastError: null,
                searchResults: summaryResult.data.entries.map(currentEntry=>
                    Object.assign({}, currentEntry,
                        {details: detailsResult.data.entries.hasOwnProperty(currentEntry.id) ? detailsResult.data.entries[currentEntry.id] : null}
                        )
                ),
                userDetails: loginDetailsResult.data
            })
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    componentWillMount(){
        this.refreshData();
    }

    render(){
        return <div>
            <div className="centered">
                <h1>{this.state.userDetails ?
                            this.state.userDetails.firstName + " " + this.state.userDetails.lastName +"'s " : ""
                    } Lightbox
                </h1>
            </div>
            {this.renderMainBody()}
            <EntryDetails entry={this.state.showingPreview}
                          autoPlay={this.state.autoPlay}
                          showJobs={true}
                          loadJobs={false}
                          lightboxedCb={this.addedToLightbox}
            />
        </div>
    }
}

export default MyLightbox;