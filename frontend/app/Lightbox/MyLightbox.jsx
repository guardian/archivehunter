import React from 'react';
import axios from 'axios';
import EntryDetails from "../Entry/EntryDetails.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import CommonSearchView from "../common/CommonSearchView.jsx";
import LightboxInfoInsert from "./LightboxInfoInsert.jsx";
import AvailabilityInsert from "./AvailabilityInsert.jsx";

class MyLightbox extends CommonSearchView {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            searchResults: [],
            userDetails: null,
            showingPreview: null,
            extraInfo: ""
        };

        this.checkArchiveStatus = this.checkArchiveStatus.bind(this);
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
            const loginDetailsResult = results[2];  //this is a map of key->value

            // console.log("detailsResult: ", detailsResult);
            // console.log("summaryResult: ", summaryResult);

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

    shouldHideAvailability(entry){
        console.log("shouldHideAvailability: ",  entry);
        if(!entry) return true;
        console.log(entry.details.restoreStatus);

        if(entry && entry.details && entry.details.restoreStatus){
            return entry.details.restoreStatus!=="RS_UNNEEDED" && entry.details.restoreStatus!=="RS_SUCCESS"
        } else {
            return true
        }
    }

    extractPath() {
        console.log(this.state.showingPreview);

        if (this.state.showingPreview) return this.state.showingPreview.path.substring(this.state.showingPreview.path.lastIndexOf('/') + 1);
        return "unknown";
    }

    checkArchiveStatus(){
        axios.get("/api/archive/status/" + this.state.showingPreview.id).then(response=>{
            console.info(response.data);
            const itemIndex = this.indexForFileid(response.data.fileId);
            if(itemIndex===-1){
                console.error("Could not find file ID " + response.data.fileId + " in the component's data??")
            } else {
                const updatedDetails = Object.assign({}, this.state.searchResults[itemIndex].details, {
                    restoreStatus: response.data.restoreStatus,
                    availableUntil: response.data.expiry
                });
                const updatedEntry = Object.assign({}, this.state.searchResults[itemIndex], {details: updatedDetails});

                this.updateSearchResults(updatedEntry, itemIndex, this.state.showingPreview.id).then(()=>{
                    if(response.data.restoreStatus==="RS_UNNEEDED"){
                        this.setState({extraInfo: "Not in archive"})
                    } else if(this.state.extraInfo!==""){
                        this.setState({extraInfo: ""})
                    }
                })
            }
        }).catch(err=>{
            console.error(err);
        })
    }

    render(){
        /**
         * this describes an "insert" into the standard entry details view, to provide lightbox-specific data
         */
        const insert = <div>
            <LightboxInfoInsert
                entry={this.state.showingPreview && this.state.showingPreview.details ?
                            this.state.showingPreview.details : null
                }
                extraInfo={this.state.extraInfo}
            />
            <p className="centered small"><a style={{cursor: "pointer"}} onClick={this.checkArchiveStatus}>Re-check</a></p>
            <hr/>
            <AvailabilityInsert status={this.state.showingPreview ? this.state.showingPreview.details.restoreStatus : ""}
                                availableUntil={this.state.showingPreview ? this.state.showingPreview.details.availableUntil : ""}
                                hidden={this.shouldHideAvailability(this.state.showingPreview)}
                                fileId={this.state.showingPreview ? this.state.showingPreview.id : null}
                                fileNameOnly={this.state.showingPreview ? this.extractPath() : null}
            />
            <hr style={{display: this.shouldHideAvailability(this.state.showingPreview) ? "inherit" : "none" }}/>
        </div>;

        return <div>
            <div className="centered">
                <h1>{this.state.userDetails ?
                            this.state.userDetails.firstName + " " + this.state.userDetails.lastName +"'s " : ""
                    } Lightbox
                </h1>
            </div>
            <EntryDetails entry={this.state.showingPreview}
                          autoPlay={this.state.autoPlay}
                          showJobs={true}
                          loadJobs={false}
                          lightboxedCb={this.addedToLightbox}
                          preLightboxInsert={insert}
            />
            <div>{this.renderMainBody()}</div>

        </div>
    }
}

export default MyLightbox;