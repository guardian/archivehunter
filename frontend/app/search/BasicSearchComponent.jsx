import React from 'react';
import axios from 'axios';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import SearchResultsComponent from './SearchResultsComponent.jsx';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class BasicSearchComponent extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            currentSearch: null,    //if a search is ongoing this will hold a cancellation token
            searching: false,
            searchTerms: "",
            error:null,
            searchResults: [],
            totalHits: -1
        };

        this.cancelTokenSource = axios.CancelToken.source();
        this.defaultPageSize = 100
    }

    componentWillMount(){

    }

    runSearch(startAt){
        const tok=this.cancelTokenSource.token;
        const encodedParamString = encodeURIComponent(this.state.searchTerms);

        this.setState({searching: true, currentSearch:tok},()=>
            axios.get("/api/search/basic?q=" + encodedParamString + "&start=" + startAt + "&length=" + this.defaultPageSize).then(result=>{
                if(result.data.entries.length===0) {  //we've run out of results
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
                    this.setState({error: err});
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

    render(){
        return <div>
            <div className="centered" style={{marginBottom: "2em"}}><FontAwesomeIcon icon="search" className="inline-icon"/><input style={{width: "95%"}} onChange={event=>this.updateSearchTerms(event.target.value)} value={this.state.searchTerms}/></div>
            {this.state.error ? <ErrorViewComponent error={this.state.error}/> : <SearchResultsComponent entries={this.state.searchResults}/>}
        </div>
    }
}

export default BasicSearchComponent;