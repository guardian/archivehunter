import React from 'react';
import axios from 'axios';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import SearchResultsComponent from './SearchResultsComponent.jsx';
import SearchSuggestionsComponent from './SearchSuggestionsComponent.jsx';

import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import EntryDetails from "../Entry/EntryDetails.jsx";

class BasicSearchComponent extends React.Component {
    searchTimeout = 1000;   //timeout in milliseconds between last keypress and the search starting

    constructor(props){
        super(props);

        this.state = {
            currentSearch: null,    //if a search is ongoing this will hold a cancellation token
            searching: false,
            searchTerms: "",
            error:null,
            searchResults: [],
            totalHits: -1,
            limit: 1000,
            showingPreview: null,
            autoPlay: true,
            searchTimer: null
        };

        this.cancelTokenSource = axios.CancelToken.source();
        this.defaultPageSize = 100;

        this.onItemOpen = this.onItemOpen.bind(this);
        this.onItemClose = this.onItemClose.bind(this);
        this.triggerSearchTimer = this.triggerSearchTimer.bind(this);
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
            axios.get("/api/search/basic?q=" + encodedParamString + "&start=" + startAt + "&length=" + this.defaultPageSize,
                {
                cancelToken: tok
            }).then(result=>{
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

    triggerSearchTimer(){
        if(this.state.searchTimer) window.clearTimeout(this.state.searchTimer);
        this.setState({searchTimer: window.setTimeout(()=>this.runSearch(0), this.searchTimeout)});
    }

    updateSearchTerms(newString){
        if(this.state.currentSearch){
            this.cancelTokenSource.cancel("New search terms")
        }
        this.setState({searchTerms: newString, searchResults: []},this.triggerSearchTimer);
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
            return <SearchResultsComponent entries={this.state.searchResults} onItemOpen={this.onItemOpen} onItemClose={this.onItemClose} selectedEntry={this.state.showingPreview}/>
        } else if(this.state.searching) {
            return <img style={{marginLeft:"auto",marginRight:"auto",width:"200px",display:"block"}} src="/assets/images/Spinner-1s-200px.gif"/>
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
            <div className="centered">
                <SearchSuggestionsComponent terms={this.state.searchTerms} autoHide={true}/>
            </div>
            <div className="centered" style={{marginBottom: "2em",height: "2em", display: this.state.totalHits===-1 ? "none":"block"}}>
                <p className="centered">Loaded {this.state.searchResults.length} of {this.state.totalHits} results{ this.state.searching ? " so far" : ""}.</p>
            </div>
            <EntryDetails entry={this.state.showingPreview} autoPlay={this.state.autoPlay} showJobs={true} loadJobs={false}/>
            {this.renderMainBody()}
        </div>
    }
}

export default BasicSearchComponent;