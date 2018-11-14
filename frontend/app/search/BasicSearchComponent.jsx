import React from 'react';
import axios from 'axios';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import SearchResultsComponent from './SearchResultsComponent.jsx';
import SearchSuggestionsComponent from './SearchSuggestionsComponent.jsx';

import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import Dialog from "react-dialog";
import EntryDetails from "../Entry/EntryDetails.jsx";

class BasicSearchComponent extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            currentSearch: null,    //if a search is ongoing this will hold a cancellation token
            searching: false,
            searchTerms: "",
            error:null,
            searchResults: [],
            totalHits: -1,
            limit: 100,
            showingPreview: null
        };

        this.cancelTokenSource = axios.CancelToken.source();
        this.defaultPageSize = 100;

        this.onItemOpen = this.onItemOpen.bind(this);
        this.onItemClose = this.onItemClose.bind(this);
    }

    componentWillMount(){

    }

    runSearch(startAt){
        const tok=this.cancelTokenSource.token;
        const encodedParamString = encodeURIComponent(this.state.searchTerms);

        if(this.state.searchTerms===""){
            this.setState({searching: false, currentSearch:null, searchResults:[], totalHits: -1});
            return;
        }

        this.setState({searching: true, currentSearch:tok},()=>
            axios.get("/api/search/basic?q=" + encodedParamString + "&start=" + startAt + "&length=" + this.defaultPageSize).then(result=>{
                if(result.data.entries.length===0 || this.state.searchResults.length>=this.state.limit) {  //we've run out of results
                    this.setState({
                        currentSearch: null,
                        searching: false,
                        error: null
                    })
                } else {
                    this.setState({
                        searchResults: this.state.searchResults.concat(result.data.entries),
                        totalHits: result.data.entryCount
                    },()=>this.runSearch(startAt+this.defaultPageSize));
                }
            }).catch(err=>{
                if(axios.isCancel(err)){
                    console.log("cancelled ongoing search");
                } else {
                    this.setState({error: err, searching: false});
                }
            })  //axios.get
        );  //this.setState
    }

    updateSearchTerms(newString){
        if(this.state.currentSearch){
            this.cancelTokenSource.cancel("New search terms")
        }
        this.setState({searchTerms: newString, searchResults: []},()=>this.runSearch(0));
    }

    onItemOpen(newTarget){
        this.setState({showingPreview: newTarget})
    }

    onItemClose(){
        this.setState({showingPreview: null});
    }

    renderMainBody(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        } else if(this.state.totalHits!==-1){
            return <SearchResultsComponent entries={this.state.searchResults} onItemOpen={this.onItemOpen} onItemClose={this.onItemClose}/>
        } else if(this.state.searching) {
            return <img style={{marginLeft:"auto",marginRight:"auto",width:"200px"}} src="/assets/images/Spinner-1s-200px.gif"/>
        } else {
            return <span/>
        }
    }

    render(){
        return <div>
            <div className="centered" style={{height: "2em"}}>
                <FontAwesomeIcon icon="search" className="inline-icon"/>
                <input style={{width: "90%"}} onChange={event=>this.updateSearchTerms(event.target.value)} value={this.state.searchTerms}/>
                <img src="/assets/images/Spinner-1s-44px.svg" style={{display: this.state.searching ? "inline-block":"none", verticalAlign: "bottom", height: "1.9em"}}/>
            </div>
            {
                this.state.showingPreview && <Dialog
                    modal={true}
                    onClose={this.onItemClose}
                    closeOnEscape={true}
                    hasCloseIcon={true}
                    isDraggable={true}
                    style={{float: "left" }}
                    position={{x: window.innerWidth/2-250, y:0}}
                    buttons={
                        [{
                            text: "Close",
                            onClick: () => this.onItemClose()
                        }]
                    }>
                    <EntryDetails entry={this.state.showingPreview}/>
                </Dialog>
            }
            <div className="centered">
                <SearchSuggestionsComponent terms={this.state.searchTerms} autoHide={true}/>
            </div>
            <div className="centered" style={{marginBottom: "2em",height: "2em", display: this.state.totalHits===-1 ? "none":"block"}}>
                <p className="centered">Found a total of {this.state.totalHits} results{ this.state.searching ? "so far" : ""}.</p>
            </div>
            {this.renderMainBody()}
        </div>
    }
}

export default BasicSearchComponent;