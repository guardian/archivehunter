import React from 'react';
import axios from 'axios';
import {Treebeard} from 'react-treebeard';
import defaultTheme from 'react-treebeard/src/themes/default';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import EntryDetails from "../Entry/EntryDetails.jsx";
import BrowsePathSummary from "./BrowsePathSummary.jsx";
import SearchManager from '../SearchManager/SearchManager.jsx';
import CommonSearchView from "../common/CommonSearchView.jsx";

class BrowseComponent extends CommonSearchView {
    constructor(props){
        super(props);

        this.state = {
            collectionName: "",
            isLoading: false,
            lastError: null,
            treeContents: [],
            cursor: null,
            collectionNames: [],
            showingPreview: null,
            autoPlay: true,
            searchResults: [],
            searching: false,
            cancelUnderway: false,
            entriesCancelTokenSource: null
        };

        this.treeStyle = Object.assign({}, defaultTheme);
        /*
        fix for treebeard bug in firefox - https://github.com/storybooks/react-treebeard/issues/148
         */
        this.treeStyle.node = Object.assign(this.treeStyle.tree.node, {
                    container: {
                        base: {
                            cursor: 'pointer',
                        },
                        link: {
                            cursor: 'pointer', position: 'relative', padding: '0px 5px', display: 'block'
                        },
                        activeLink: {
                            background: '#31363F'
                        }
                    }
        });

        this.onToggle = this.onToggle.bind(this);
        this.onItemClose = this.onItemClose.bind(this);
        this.onItemOpen = this.onItemOpen.bind(this);
        this.addedToLightbox = this.addedToLightbox.bind(this);

        /* callbacks for Searcher */
        this.receivedNextPage = this.receivedNextPage.bind(this);
        this.searchError = this.searchError.bind(this);
        this.searchCancelled = this.searchCancelled.bind(this);
        this.searchCompleted = this.searchCompleted.bind(this);

        this.searchManager = new SearchManager();
    }

    refreshCollectionNames(){
        this.setState({isLoading: true, lastError: null}, ()=>axios.get("/api/browse/collections").then(result=>{
            const nameList = result.data.entries;
            this.setState({isLoading: false, collectionNames: nameList, collectionName: nameList.length>0 ? nameList[0] : null}, ()=>this.refreshTreeContents());
        }).catch(err=>{
            console.error("Could not refresh collection names: ", err);
            this.setState({isLoading: false, lastError: err});
        }))
    }

    static treeDataForEntry(entry, parent){
        const pathParts = entry.split("/");
        const nodeName = pathParts.length>1 ? pathParts[pathParts.length-2] : pathParts[1];

        return {
            name: nodeName,
            loading: false,
            children: [],
            toggled: false,
            fullPath: entry,
            isLoaded: false
        }
    }

    refreshTreeContents(){
        this.setState({isLoading: true, lastError: null, treeContents: []}, ()=>axios.get("/api/browse/" + this.state.collectionName + "?prefix=").then(result=>{
            this.setState({isLoading: false, lastError: null, treeContents: result.data.entries.map(ent=>BrowseComponent.treeDataForEntry(ent))}, ()=>this.triggerSearch())
        }).catch(err=>{
            console.error("Could not list folders: ", err);
            this.setState({isLoading: false, lastError: err});
        }))
    }

    loadSubFolder(node){
        return new Promise((resolve,reject)=> {
            console.log("loadSubFolder: ", node.fullPath);

            node.loading = true;
            this.setState({loading: true, lastError: null}, () =>
                axios.get("/api/browse/" + this.state.collectionName + "?prefix=" + encodeURIComponent(node.fullPath))
                    .then(response => {
                        node.loading = false;
                        node.isLoaded = true;
                        node.children = response.data.entries.length ?
                            response.data.entries.map(ent => BrowseComponent.treeDataForEntry(ent, node.fullPath)) :
                            null;
                        this.setState({loading: false}, ()=>resolve());
                    }).catch(err => {
                        node.loading = false;
                        this.console.error(err);
                        this.setState({loading: false, lastError: err},()=>reject(err));
                }));
        })
    }

    makeSearchJson(node) {
        if(node) {
            const pathToSearch = node.fullPath.endsWith("/") ? node.fullPath.slice(0, node.fullPath.length - 1) : node.fullPath;
            console.log("pathToSearch is " + pathToSearch);
            return JSON.stringify({q: null, path: pathToSearch, collection: this.state.collectionName});
        } else {
            return JSON.stringify({q: null, path: null, collection: this.state.collectionName});
        }
    }

    receivedNextPage(response, searchId){
        if(searchId===this.state.currentSearch) {
            this.setState({searchResults: this.state.searchResults.concat(response.data.entries)});
        } else {
            console.error("Received data for stale search " + searchId + ". Current search is " + this.state.currentSearch)
        }
        return this.state.searchResults.length<450;
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
     * callback, called by Searcher when a search has completed (returns no more results)
     * @param searchId
     */
    searchCompleted(searchId){
        console.log("Search completed: " + searchId);
        this.setState({loading: false, lastError: null});
    }

    /**
     * callback, called by Searcher when a search has been cancelled (i.e. superceded)
     * @param searchId
     */
    searchCancelled(searchId){
        console.log("Search cancelled: " + searchId);
        this.setState({searchResults:[]});
    }

    searchError(error, searchId){
        console.error(error);
        this.setState({loading: false, lastError: error});
    }

    triggerSearch(node, startingPos){
        const pageSize = 100;
        const toSend = {
            data: this.makeSearchJson(node),
            contentType: "application/json"
        };

        this.searchManager.makeNewSearch("POST","/api/search/browser",null,toSend,pageSize,this.receivedNextPage, this.searchCompleted, this.searchCancelled,this.searchError)
            .then(searchId=>{
                console.log("Got new search ID " + searchId);
                this.setState({currentSearch: searchId})
            });
    }

    postToggle(node){
        if (node.isLoaded) {
            this.triggerSearch(node);
        } else {
            this.loadSubFolder(node).then(() => this.triggerSearch(node));
        }
    }

    onToggle(node, toggled){
        if(this.state.cursor){this.state.cursor.active = false;}
        node.active = true;
        if(node.children){ node.toggled = toggled; }

        if(this.state.cursor!==node) {
            this.setState({cursor: node, searchResults: []}, () => {
                this.postToggle(node);
            });
        }
    }

    componentDidUpdate(oldProps, oldState){
        if(oldState.collectionName!==this.state.collectionName) this.refreshTreeContents();
    }

    componentWillMount(){
        this.refreshCollectionNames();
    }

    renderMainBody(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        } else if(this.state.totalHits!==-1){
            return <div>
                <BrowsePathSummary collectionName={this.state.collectionName} path={this.state.cursor ? this.state.cursor.fullPath : null}/>
                <SearchResultsComponent entries={this.state.searchResults} onItemOpen={this.onItemOpen} onItemClose={this.onItemClose} selectedEntry={this.state.showingPreview} cancelToken={this.entriesCancelTokenSource ? this.entriesCancelTokenSource.token : null}/>
            </div>
        } else if(this.state.searching) {
            return <img style={{marginLeft:"auto",marginRight:"auto",width:"200px",display:"block"}} src="/assets/images/Spinner-1s-200px.gif"/>
        } else {
            return <span/>
        }
    }

    doCancelAll(){
        if(!this.state.cancelUnderway) {
            this.setState({cancelUnderway: true}, () => {
                this.cancelTokenSource.cancel("User request");
                window.setTimeout(() => this.setState({cancelUnderway: false}), 3000)
            })
        }
    }

    render() {
        return <div>
            <div className="selector-panel" style={{overflow: this.state.treeContents.length>0 ? "scroll" : "hidden"}}>
                <select id="collection-selector"
                        value={this.state.collectionName}
                        onChange={evt=>this.setState({collectionName: evt.target.value, searchResults: [], path: null})}>
                    {
                        this.state.collectionNames.map(entry=><option id={entry} value={entry}>{entry}</option>)
                    }
                </select>
                <a style={{display: "none"}} onClick={this.doCancelAll}>{this.state.cancelUnderway ? "cancelling..." : "cancel all"}</a>
                <Treebeard data={this.state.treeContents} onToggle={this.onToggle} style={this.treeStyle}/>
            </div>
            <EntryDetails entry={this.state.showingPreview} autoPlay={this.state.autoPlay} showJobs={true} loadJobs={false} lightboxedCb={this.addedToLightbox}/>
            {this.renderMainBody()}
        </div>
    }
}

export default BrowseComponent;