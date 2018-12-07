import React from 'react';
import axios from 'axios';
import {Treebeard} from 'react-treebeard';
import defaultTheme from 'react-treebeard/src/themes/default';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import SearchResultsComponent from "../search/SearchResultsComponent.jsx";
import EntryDetails from "../Entry/EntryDetails.jsx";

class BrowseComponent extends React.Component {
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
            searching: false
        };

        this.treeStyle = Object.assign({}, defaultTheme);
        /*
        fix for treebeard bug in firefox - https://github.com/storybooks/react-treebeard/issues/148
         */
        this.treeStyle.node = Object.assign(this.treeStyle.tree.node, {
                    container: {
                        link: {
                            cursor: 'pointer', position: 'relative', padding: '0px 5px', display: 'block'
                        },
                        activeLink: {
                            background: '#31363F'
                        }
                    }
        });

        this.cancelTokenSource = axios.CancelToken.source();

        this.onToggle = this.onToggle.bind(this);
        this.onItemClose = this.onItemClose.bind(this);
        this.onItemOpen = this.onItemOpen.bind(this);
    }

    refreshCollectionNames(){
        this.setState({isLoading: true, lastError: null}, ()=>axios.get("/api/scanTarget").then(result=>{
            const nameList = result.data.entries.map(ent=>ent.bucketName);
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

    triggerSearch(node, startingPos){
        const tok=this.cancelTokenSource.token;

        const startAt = startingPos ? startingPos : 0;
        const pageSize = 100;

        const toSend = this.makeSearchJson(node);

        this.setState({loading: true, lastError: null, currentSearchToken: tok}, ()=>
            axios.post("/api/search/browser?start=" + startAt + "&size=" + pageSize, toSend, {headers: {"Content-Type": "application/json"}, cancelToken: tok}).then(response=>{
                console.log("Got " + response.data.entries.length + " results to add to " + this.state.searchResults.length + " already existing");
                if(response.data.entries.length>0 && this.state.searchResults.length < 1000 && startingPos){
                    this.setState({searchResults: this.state.searchResults.concat(response.data.entries)}, ()=>this.triggerSearch(node, startAt+pageSize));
                } else if(!startingPos) {
                    this.setState({searchResults: response.data.entries}, ()=>this.triggerSearch(node, startAt+pageSize));
                } else {
                    this.setState({loading: false, lastError: null});
                }
            }).catch(err=>{
                if(axios.isCancel(err)) {
                    console.log("cancelled ongoing search");
                } else {
                    console.error(err);
                    this.setState({loading: false, lastError: err});
                }
            })
        )
    }

    onToggle(node, toggled){
        if(this.state.cursor){this.state.cursor.active = false;}
        node.active = true;
        if(node.children){ node.toggled = toggled; }
        this.setState({ cursor: node }, ()=>{
            if(node.isLoaded) {
                if(this.state.currentSearchToken){
                    this.cancelTokenSource.cancel("New search terms")
                }
                this.triggerSearch(node);
            } else {
                this.loadSubFolder(node).then(()=>this.triggerSearch(node));
            }
        });
    }

    componentDidUpdate(oldProps, oldState){
        if(oldState.collectionName!==this.state.collectionName) this.refreshTreeContents();
    }

    componentWillMount(){
        this.refreshCollectionNames();
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

    render() {
        return <div>
            <div id="selector-panel" style={{width: "20%", height: "90vh", overflow: this.state.treeContents.length>0 ? "scroll" : "hidden", float: "left"}}>
                <select id="collection-selector"
                        value={this.state.collectionName}
                        onChange={evt=>this.setState({collectionName: evt.target.value})}>
                    {
                        this.state.collectionNames.map(entry=><option id={entry} value={entry}>{entry}</option>)
                    }
                </select>
                <Treebeard data={this.state.treeContents} onToggle={this.onToggle} style={this.treeStyle}/>
            </div>
            <EntryDetails entry={this.state.showingPreview} autoPlay={this.state.autoPlay} showJobs={true} loadJobs={false}/>
            {this.renderMainBody()}
        </div>
    }
}

export default BrowseComponent;