import React from "react";
import axios from "axios";
import BytesFormatter from "../common/BytesFormatter.jsx";
import RefreshButton from "../common/RefreshButton.jsx";

class QuickRestoreComponent extends React.Component {
    constructor(props) {
        super(props);

        this.state = {
            isLoading: false,
            isLightboxing: false,
            quotaExceeded: false,
            quotaRequired: 0,
            quotaLevel: 0,
            lastError: null,
            requestedPath: "",
            collectionName: "",
            fileCount: 0,
            totalSize: 0
        }
        this.doPathCheck = this.doPathCheck.bind(this);
        this.doRestore = this.doRestore.bind(this);
    }

    makeSearchJson() {
        return JSON.stringify({
            hideDotFiles: false,
            collection: this.state.collectionName,
            path: this.state.requestedPath
        })
    }

    static getDerivedStateFromError(error) {
        console.error(error);
        return {
            isLoading: false,
            isLightboxing: false,
            quotaExceeded: false,
            quotaRequired: 0,
            quotaLevel: 0,
            requestedPath: "",
            collectionName: "",
            fileCount: 0,
            totalSize: 0,
            lastError: "A frontend error occurred, probably a bug. See the console for details."
        }
    }

    doPathCheck() {
        console.log("doPathCheck");

        const pathToSearch = this.state.requestedPath.endsWith("/") ? this.state.requestedPath.slice(0, this.state.requestedPath.length - 1) : this.state.requestedPath;

        const urlsuffix = pathToSearch ? "?prefix=" + encodeURIComponent(pathToSearch) : "";
        const url = "/api/browse/" + this.state.collectionName + "/summary" + urlsuffix;

        this.setState({isLoading:true, lastError: null},
            ()=>axios.put(url, this.makeSearchJson(), {headers: {"Content-Type": "application/json"}}).then(response=>{
                this.setState({
                    isLoading:false,
                    lastError:null,
                    fileCount: response.data.totalHits,
                    totalSize: response.data.totalSize,
                })
            }).catch(err=>{
                console.error("Could not refresh path summary data: ", err);
                this.setState({loading: false,lastError: err.toString()})
            }))
    }

    doRestore() {
        console.log("doLightbox");
        this.setState({isLightboxing: true, lastError:null},
            ()=>axios.put("/api/lightbox/my/addFromSearch", this.makeSearchJson(),{headers:{"Content-Type":"application/json"}}).then(response=>{
                console.log(response.data);
                this.setState({isLightboxing:false});
            }).catch(err=>{
                if(err.response && err.response.status===413){
                    console.log(err.response.data);
                    this.setState({
                        isLightboxing: false,
                        quotaExceeded: true,
                        quotaRequired: err.response.data.requiredQuota,
                        quotaLevel: err.response.data.actualQuota
                    })
                } else {
                    this.setState({loading: false, lastError: err});
                }
            })
        )
    }

    render() {
        return (
            <div>
                <ul className="collections-list">
                    <li>
                        <label htmlFor="collection-input">Collection name to restore from:</label>
                        <input className="wide"
                               id="collection-input"
                               value={this.state.collectionName}
                               onChange={(evt)=>this.setState({collectionName: evt.target.value})}
                               />
                    </li>
                    <li>
                        <label htmlFor="path-input">Path to restore:</label>
                        <input className="wide"
                               id="path-input"
                               value={this.state.requestedPath}
                               onChange={(evt)=>this.setState({requestedPath: evt.target.value})}
                               />
                    </li>
                    <li>
                        <>
                            <button onClick={this.doPathCheck} disabled={this.state.isLoading}>Check path</button>
                            {this.state.isLoading ? <RefreshButton isRunning={true} clickedCb={()=>{}}/> : null}
                        </>
                    </li>
                    <li>
                        <p>There are {this.state.fileCount} files in the path selected totalling <BytesFormatter value={this.state.totalSize}/></p>
                    </li>
                    <li>
                        <>
                            <button disabled={this.state.isLoading || this.state.fileCount===0} onClick={this.doRestore}>Add to Lightbox</button>
                            {this.state.isLightboxing ? <RefreshButton isRunning={true} clickedCb={()=>{}}/> : null}
                            {this.state.quotaExceeded ?
                                <p className="error-text">This restore would exceed your quota of <BytesFormatter value={this.state.quotaLevel}/>.
                                    You need <BytesFormatter value={this.state.quotaRequired}/></p> : null
                            }
                        </>
                    </li>
                    <li>
                        {
                            this.state.lastError ? <p className="error-text">{this.state.lastError}</p> : null
                        }
                    </li>
                </ul>
            </div>
        )
    }
}

export default QuickRestoreComponent;