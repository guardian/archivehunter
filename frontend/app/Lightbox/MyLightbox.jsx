import React from 'react';
import axios from 'axios';
import EntryDetails from "../Entry/EntryDetails.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import CommonSearchView from "../common/CommonSearchView.jsx";
import LightboxInfoInsert from "./LightboxInfoInsert.jsx";
import AvailabilityInsert from "./AvailabilityInsert.jsx";
import BulkSelectionsScroll from "./BulkSelectionsScroll.jsx";
import UserSelector from "../common/UserSelector.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";

class MyLightbox extends CommonSearchView {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            searchResults: [],
            userDetails: null,
            showingPreview: null,
            extraInfo: "",
            bulkSelections: [],
            bulkSelectionsCount: 0,
            bulkSelectionSelected: null,
            selectedUser: "my",
            showingArchiveSpinner: false,
            selectedRestoreStatus: null
        };

        this.checkArchiveStatus = this.checkArchiveStatus.bind(this);
        this.bulkSelectionChanged = this.bulkSelectionChanged.bind(this);
        this.reloadSearch = this.reloadSearch.bind(this);
        this.bulkSearchDeleteRequested = this.bulkSearchDeleteRequested.bind(this);
        this.userChanged = this.userChanged.bind(this);
        this.redoRestore = this.redoRestore.bind(this);
    }

    performLoad(){
        const detailsRequest = axios.get("/api/lightbox/" + this.state.selectedUser+"/details");
        const summaryRequest = axios.get("/api/search/myLightBox?user=" + this.state.selectedUser);
        const loginDetailsRequest = axios.get("/api/loginStatus");
        const bulkSelectionsRequest = axios.get("/api/lightbox/" + this.state.selectedUser+"/bulks");
        return Promise.all([detailsRequest, summaryRequest, loginDetailsRequest, bulkSelectionsRequest]);
    }

    userChanged(evt){
        this.setState({selectedUser: evt.target.value}, ()=>this.refreshData());
    }

    refreshData(){
        this.setState({loading: true}, ()=>this.performLoad().then(results=>{
            const detailsResult = results[0];   //this is a map of fileId->record
            const summaryResult = results[1];   //this is an array
            const loginDetailsResult = results[2];  //this is a map of key->value
            const bulkSelectionsResult = results[3];    //this is an object with "entries" and "entryCount" fields

            this.setState({loading: false,
                lastError: null,
                searchResults: summaryResult.data.entries.map(currentEntry=>
                    Object.assign({}, currentEntry,
                        {details: detailsResult.data.entries.hasOwnProperty(currentEntry.id) ? detailsResult.data.entries[currentEntry.id] : null}
                        )
                ),
                userDetails: loginDetailsResult.data,
                bulkSelections: bulkSelectionsResult.data.entries,
                bulkSelectionsCount: bulkSelectionsResult.data.entryCount
            })
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    reloadSearch(){
        const detailsRequest = axios.get("/api/lightbox/" + this.state.selectedUser + "/details");
        const searchUrl = this.state.bulkSelectionSelected ?
            "/api/search/myLightBox?bulkId=" + this.state.bulkSelectionSelected + "&user=" + this.state.selectedUser :
            "/api/search/myLightBox?user="+ this.state.selectedUser;

        const searchRequest = axios.get(searchUrl);

        const loadingPromise = Promise.all([detailsRequest, searchRequest]);

        this.setState({searchResults:[], loading: true, lastError:null}, ()=>loadingPromise.then(results=>{
            const detailsResult = results[0];
            const searchResult = results[1];

            this.setState({
                loading: false,
                lastError: null,
                searchResults: searchResult.data.entries.map(currentEntry=>
                    Object.assign({}, currentEntry,
                        {details: detailsResult.data.entries.hasOwnProperty(currentEntry.id) ? detailsResult.data.entries[currentEntry.id] : null}
                    )
                )
            })
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err})
        }))
    }

    bulkSearchDeleteRequested(entryId){
        axios.delete("/api/lightbox/bulk/" + entryId).then(response=>{
            console.log("lightbox entry " + entryId + " deleted.");
            const updatedSelected = this.state.bulkSelectionSelected===entryId ? null : this.state.bulkSelectionSelected;

            this.setState({
                bulkSelections: this.state.bulkSelections.filter(entry=>entry.id !== entryId),
                bulkSelectionSelected: updatedSelected
            }, this.reloadSearch)
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err});
        })
    }

    componentWillMount(){
        this.refreshData();
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(prevState.showingPreview!==this.state.showingPreview) this.setState({selectedRestoreStatus: null});
    }

    bulkSelectionChanged(newValue){
        this.setState({bulkSelectionSelected: newValue, selectedRestoreStatus: null}, this.reloadSearch);
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

    redoRestore() {
        const url = "/api/lightbox/" + this.state.selectedUser + "/redoRestore/" + this.state.showingPreview.id;
        this.setState({showingArchiveSpinner: true}, ()=>axios.put(url).then(response=>{
            console.info(response.data);
            window.setTimeout(this.checkArchiveStatus, 3000);
        }).catch(err=>{
            console.error(err);
            this.setState({showingArchiveSpinner: false, selectedRestoreStatus: err.response.data && err.response.data.detail ? err.response.data.detail : ""});
        }));
    }

    checkArchiveStatus(){
        axios.get("/api/archive/status/" + this.state.showingPreview.id + "?user=" + this.state.selectedUser).then(response=>{
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
                        this.setState({showingArchiveSpinner: false, selectedRestoreStatus: response.data.restoreStatus, extraInfo: "Not in deep-freeze"})
                    } else if(this.state.extraInfo!==""){
                        this.setState({showingArchiveSpinner: false, selectedRestoreStatus: response.data.restoreStatus, extraInfo: ""})
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
            <p className="centered small"><a style={{cursor: "pointer"}} onClick={this.redoRestore}>Redo restore</a><LoadingThrobber show={this.state.showingArchiveSpinner} small={true}/> </p>
            <p className="centered small information" style={{display: this.state.selectedRestoreStatus ? "block": "none"}}>
                {this.state.selectedRestoreStatus}
            </p>
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

            {
                this.state.userDetails && this.state.userDetails.isAdmin ? <div style={{float: "right"}}>
                    <UserSelector onChange={this.userChanged} selectedUser={this.state.selectedUser}/>
                </div> : ""
            }

            <BulkSelectionsScroll entries={this.state.bulkSelections}
                                  onSelected={this.bulkSelectionChanged}
                                  onDeleteClicked={this.bulkSearchDeleteRequested}
                                  currentSelection={this.state.bulkSelectionSelected}/>
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