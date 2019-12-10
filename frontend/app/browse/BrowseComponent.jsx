import React from 'react';
import axios from 'axios';
import {Treebeard} from 'react-treebeard';
import defaultTheme from 'react-treebeard/dist/themes/default';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import EntryDetails from "../Entry/EntryDetails.jsx";
import BrowsePathSummary from "./BrowsePathSummary.jsx";
import SearchManager from '../SearchManager/SearchManager.jsx';
import CommonSearchView from "../common/CommonSearchView.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";

class BrowseComponent extends CommonSearchView {
    constructor(props){
        super(props);

        this.state = {
            collectionName: "",
            isLoading: false,
            lastError: null,
            openedPath: [],
            treeContents: [],
            cursor: null,
            collectionNames: [],
            showingPreview: null,
            autoPlay: true,
            searchResults: [],
            searching: false,
            cancelUnderway: false,
            entriesCancelTokenSource: null,
            sortOrder: "Ascending",
            sortField: "last_modified",
            showDotFiles: false,
            currentLoadTarget: 5,   //how many more items we are expecting to get
            visiblePageSize: 5      //the maximum number of items to load in each time "Load more" is clicked. If the page size from server is greater, then it supercedes the value here
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

        this.loadMoreClicked = this.loadMoreClicked.bind(this);

        /* callback for BrowsePathSummary */
        this.refreshContents = this.refreshContents.bind(this);
        this.goToRoot = this.goToRoot.bind(this);
        this.sortUpdated = this.sortUpdated.bind(this);

        /* callbacks for Searcher */
        this.receivedNextPage = this.receivedNextPage.bind(this);
        this.searchError = this.searchError.bind(this);
        this.searchCancelled = this.searchCancelled.bind(this);
        this.searchCompleted = this.searchCompleted.bind(this);

        this.searchManager = new SearchManager();
    }

    refreshCollectionNames(){
        return new Promise((resolve,reject)=> {
            this.setState({
                isLoading: true,
                lastError: null
            }, () => axios.get("/api/browse/collections").then(result => {
                const nameList = result.data.entries;
                let toSet = {
                    isLoading: false,
                    collectionNames: nameList,
                };
                if(this.state.collectionName==="" || ! this.state.collectionName) toSet.collectionName= nameList.length > 0 ? nameList[0] : null;
                this.setState(toSet, () => this.refreshTreeContents().then(()=>resolve()));
            }).catch(err => {
                console.error("Could not refresh collection names: ", err);
                this.setState({isLoading: false, lastError: err}, ()=>reject(err));
            }))
        });
    }

    static arrayCompare(arr1,arr2){
        for(var i=0;i<arr1.length;++i){
            if(arr2[i]!==arr1[i]) return false;
        }
        return true;
    }

    static treeDataForEntry(entry, parent, openedPath){
        const pathParts = entry.split("/");
        const nodeName = pathParts.length>1 ? pathParts[pathParts.length-2] : pathParts[1];

        const shouldToggle = openedPath.length>0 && this.arrayCompare(openedPath.slice(0,pathParts.length-1),pathParts.slice(0,pathParts.length-1));
        // console.log("openedPath: ",openedPath.slice(0,pathParts.length-1));
        // console.log("pathParts: ", pathParts.slice(0,pathParts.length-1));
        // console.log("shouldToggle: ", shouldToggle);

        return {
            name: nodeName,
            loading: false,
            children: [],
            toggled: shouldToggle,
            fullPath: entry,
            isLoaded: false
        }
    }

    /**
     * recursively loads nodes down a specific path.  Used when opening a specific item ID; the path to the item is automatically
     * expanded and the state of the component set appropriately
     * @param parentNode the tree node to work on.  this must have the `children` property; for an initial call fake this as {children: <nodeList>}
     * @param pathParts array representation of the path being opened
     * @param index which level of path we are opening. For an initial call set this to zero
     * @returns {Promise} a Promise, which resolves with the pointer to the _terminal_ node (i.e., the last one opened) once the whole tree has been loaded.
     */
     loadNextNodeOfSpecific(parentNode, pathParts, index){
        return new Promise((resolve,reject)=> {
            if (index >= pathParts.length || !parentNode.children){
                resolve(parentNode);
                return;
            }
            //console.log("parentNode", parentNode);
            const childNode = parentNode.children.find(node => node.name === pathParts[index]);
            if (!childNode) {
                console.error("Could not find relevant child node");
                console.error(parentNode);
                console.error(pathParts);
                console.error(pathParts[index]);
                console.error(index);
                reject("Could not find relevant child node");
            } else {
                this.loadSubFolder(childNode).then(() => this.loadNextNodeOfSpecific(childNode, pathParts, index + 1).then((terminalNode)=>resolve(terminalNode)).catch(err=>reject(err)));
            }
        });
    }

    loadSpecificTreePath(){
        return new Promise((resolve, reject)=>{
            if(this.state.openedPath && this.state.openedPath.length>0){
                this.loadNextNodeOfSpecific({children: this.state.treeContents}, this.state.openedPath, 0).then(terminalNode=>{
                    this.triggerSearch(terminalNode).then(()=>resolve()).catch(err=>reject(err));
                }).catch(err=>reject(err));
            } else {
                this.triggerSearch().then(()=>resolve()).catch(err=>reject(err));
            }
        });
    }

    loadMoreClicked(){
         if(this.searchManager){
             this.setState({currentLoadTarget: this.state.currentLoadTarget + this.state.visiblePageSize}, ()=>this.searchManager.resumeSearch());
         } else {
             console.error("Cannot resume, no search manager present");
         }
    }

    refreshTreeContents(){
        return new Promise((resolve, reject)=> {
            this.setState({
                isLoading: true,
                lastError: null,
                treeContents: []
            }, () => axios.get("/api/browse/" + this.state.collectionName + "?prefix=").then(result => {
                this.setState({
                    isLoading: false,
                    lastError: null,
                    treeContents: result.data.entries.map(ent => BrowseComponent.treeDataForEntry(ent, undefined, this.state.openedPath))
                }, () => this.loadSpecificTreePath().then(()=>resolve()));
            }).catch(err => {
                console.error("Could not list folders: ", err);
                this.setState({isLoading: false, lastError: err}, ()=>reject());
            }))
        });
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
                            response.data.entries.map(ent => BrowseComponent.treeDataForEntry(ent, node.fullPath, this.state.openedPath)) :
                            null;
                        this.setState({loading: false}, ()=>resolve());
                    }).catch(err => {
                        node.loading = false;
                        console.error(err);
                        this.setState({loading: false, lastError: err},()=>reject(err));
                }));
        })
    }

    makeSearchJson(node) {
        if(node) {
            const pathToSearch = node.fullPath.endsWith("/") ? node.fullPath.slice(0, node.fullPath.length - 1) : node.fullPath;
            return JSON.stringify({q: null,
                path: pathToSearch,
                collection: this.state.collectionName,
                sortBy: this.state.sortField,
                sortOrder: this.state.sortOrder,
                hideDotFiles: !this.state.showDotFiles
            });
        } else {
            return JSON.stringify({q: null,
                path: null,
                collection: this.state.collectionName,
                sortBy: this.state.sortField,
                sortOrder: this.state.sortOrder,
                hideDotFiles: !this.state.showDotFiles
            });
        }
    }

    receivedNextPage(response, searchId){
        if(searchId===this.state.currentSearch) {
            this.setState({
                searchResults: this.state.searchResults.concat(
                    response.data.entries.filter(ent=>ent.mimeType.minor!=="x-directory")
                )
            });
        } else {
            console.error("Received data for stale search " + searchId + ". Current search is " + this.state.currentSearch)
        }
        return this.state.searchResults.length<this.state.currentLoadTarget;
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
        return new Promise((resolve,reject)=>{
            const pageSize = 20;
            const toSend = {
                data: this.makeSearchJson(node),
                contentType: "application/json"
            };

            this.searchManager.makeNewSearch("POST","/api/search/browser",null,toSend,pageSize,this.receivedNextPage, this.searchCompleted, this.searchCancelled,this.searchError)
                .then(searchId=>{
                    console.log("Got new search ID " + searchId);
                    this.setState({currentSearch: searchId}, resolve())
                });
        });
    }

    postToggle(node){
        if (node.isLoaded) {
            this.setState({openedPath: node.fullPath.split("/").slice(0,-1)}, ()=>this.triggerSearch(node));
        } else {
            this.loadSubFolder(node).then(() => this.setState({openedPath: node.fullPath.split("/").slice(0,-1)}, ()=>this.triggerSearch(node)));
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
        const qp = this.breakdownQueryParams();

        if(qp.hasOwnProperty("open")) {   //request to open a specific id
            axios.get("/api/entry/" + qp.open).then(response=>{
                this.setState({
                    showingPreview: response.data.entry,
                    collectionName: response.data.entry.bucket,
                    openedPath: response.data.entry.path.split("/")
                }, ()=>this.refreshCollectionNames());
            }).catch(err=>{
                console.error(err);
                this.setState({error:err});
            })
        } else {
            this.refreshCollectionNames();
        }
    }

    sortUpdated(newSortField,newSortOrder) {
        console.debug("sortUpdated: ", newSortField, "; ", newSortOrder);
        this.setState({sortField: newSortField, sortOrder: newSortOrder, searchResults: []}, ()=>this.loadSpecificTreePath());
    }

    refreshContents() {
        console.debug("refreshContents");
        this.setState({searchResults:[]}, this.loadSpecificTreePath);
    }

    goToRoot() {
        this.setState({cursor: null, searchResults: [], openedPath: []}, ()=>this.loadSpecificTreePath());
    }

    renderMainBody(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        } else if(this.state.totalHits!==-1){
            return <div>
                <BrowsePathSummary collectionName={this.state.collectionName}
                                   path={this.state.cursor ? this.state.cursor.fullPath : null}
                                   onSortChanged={this.sortUpdated}
                                   sortField={this.state.sortField}
                                   sortOrder={this.state.sortOrder}
                                   parentIsLoading={this.state.isLoading || this.state.searching}
                                   refreshCb={this.refreshContents}
                                   goToRootCb={this.goToRoot}
                                   showDotFiles={this.state.showDotFiles}
                                   showDotFilesUpdated={value=>this.setState({showDotFiles: value}, this.refreshContents)}
                />
                <SearchResultsComponent entries={this.state.searchResults}
                                        onItemOpen={this.onItemOpen}
                                        onItemClose={this.onItemClose}
                                        selectedEntry={this.state.showingPreview}
                                        cancelToken={this.entriesCancelTokenSource ? this.entriesCancelTokenSource.token : null}
                                        loadMoreClicked={this.loadMoreClicked}
                                        showLoadMore={true}/>
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

    breakdownQueryParams(){
        if(this.props.location.search){
            const parts = this.props.location.search.substr(1).split("&");
            return parts.reduce((acc,entry)=>{
                const kv = entry.split("=");
                let update = {};
                update[kv[0]] = kv.slice(1).join("=");
                return Object.assign({}, acc, update)
            }, {});
        } else {
            return {}
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
                <div className="fixed-row">
                    <LoadingThrobber show={this.state.isLoading} small={true} caption="Loading..."/>
                    <p className="information" style={{display: this.state.treeContents.length>0 || this.state.isLoading ? "none":"inline"}}>no folders to display</p>
                </div>
                <a style={{display: "none"}} onClick={this.doCancelAll}>{this.state.cancelUnderway ? "cancelling..." : "cancel all"}</a>
                <Treebeard data={this.state.treeContents} onToggle={this.onToggle} style={this.treeStyle}/>
            </div>
            <EntryDetails entry={this.state.showingPreview} autoPlay={this.state.autoPlay} showJobs={true} loadJobs={false} lightboxedCb={this.addedToLightbox}/>
            {this.renderMainBody()}
        </div>
    }
}

export default BrowseComponent;