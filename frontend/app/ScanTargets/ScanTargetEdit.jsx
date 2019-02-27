import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import axios from 'axios';
import {Redirect} from 'react-router-dom';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import RegionSelector from "../common/RegionSelector.jsx";
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";
import TranscoderCheckComponent from "./TranscoderCheckComponent.jsx";
import ReactTooltip from 'react-tooltip';
import JobEntry from "../common/JobEntry.jsx";

class ScanTargetEdit extends React.Component {
    constructor(props){
        super(props);

        const defaultValues = {
            bucketName: "",
            proxyBucket: "",
            enabled: false,
            region: "eu-west-1",
            lastScanned: null,
            scanInterval: 7200,
            scanInProgress: false,
            lastError: "",
            pendingJobIds: [],
            paranoid: false
        };

        this.state = {
            entry: defaultValues,
            idToLoad: null,
            loading: false,
            error: null,
            formValidationErrors: [],
            completed: false
        };

        this.updateBucketname = this.updateBucketname.bind(this);
        this.updateProxyBucket = this.updateProxyBucket.bind(this);
        this.updateRegion = this.updateRegion.bind(this);
        this.toggleEnabled = this.toggleEnabled.bind(this);
        this.timeIntervalUpdate = this.timeIntervalUpdate.bind(this);
        this.toggleParanoid = this.toggleParanoid.bind(this);

        this.formSubmit = this.formSubmit.bind(this);

        this.updatePendingJobs = this.updatePendingJobs.bind(this);
    }

    loadData(idToLoad){
        return new Promise((resolve, reject)=>
        this.setState({error:null, loading:true, idToLoad: idToLoad},
            ()=>axios.get("/api/scanTarget/" + encodeURIComponent(idToLoad))
                .then(response=> {
                    this.setState({loading: false, error: null,
                        entry: response.data.entry,
                        pendingJobIds: response.data.entry.pendingJobIds ? response.data.entry.pendingJobIds : []}, ()=>resolve())
                })
                .catch(err=>{
                    console.error(err);
                    this.setState({loading: false, error: err, entry: null}, ()=>reject())
                })
        ))
    }

    updatePendingJobs(){
        if(this.state.loading) return;

        this.setState({error:null, loading:true},
            ()=>axios.get("/api/scanTarget/" + encodeURIComponent(this.state.idToLoad))
                .then(response=>{
                    this.setState({loading: false, error: null, pendingJobIds: response.data.entry.pendingJobIds ? response.data.entry.pending : []})
                })
                .catch(err=>{
                    console.error(err);
                    this.setState({loading: false, error: err, entry: null})
                })
        );
    }

    componentWillMount(){
        const idToLoad = this.props.location.pathname.split('/').slice(-1)[0];
        console.log("going to load from id", idToLoad);

        if(idToLoad!=="new"){
            this.loadData(idToLoad).then(()=>window.setInterval(this.updatePendingJobs,3000));
        } else {

        }
    }

    updateBucketname(evt){
        //this is annoying, but a necessity to avoid modifying this.state.entry directly and selectively over-write the key.
        const newEntry = Object.assign({}, this.state.entry, {bucketName: evt.target.value});
        this.setState({entry: newEntry},()=>console.log("state has been set"));
    }

    updateProxyBucket(evt){
        const newEntry = Object.assign({}, this.state.entry, {proxyBucket: evt.target.value});
        this.setState({entry:newEntry},()=>console.log("state has been set"));
    }

    updateRegion(evt){
        console.log("updateRegion: ", evt.target);
        const newEntry = Object.assign({}, this.state.entry, {region: evt.target.value});
        this.setState({entry: newEntry},()=>console.log("state has been set"));
    }

    toggleEnabled(evt){
        const newEntry = Object.assign({}, this.state.entry, {enabled: !this.state.entry.enabled});
        this.setState({entry: newEntry});
    }

    toggleParanoid(evt){
        const newEntry = Object.assign({}, this.state.entry, {paranoid: !this.state.entry.paranoid});
        this.setState({entry: newEntry});
    }

    clearErrorLog(evt){
        const newEntry = Object.assign({lastError: null}, this.state.entry);
        this.setState({entry: newEntry});
    }

    timeIntervalUpdate(newValue){
        console.log("time interval updated to " + newValue + " seconds");
        const newEntry = Object.assign({}, this.state.entry, {scanInterval: newValue});
        this.setState({entry: newEntry});
    }

    formSubmit(evt){
        evt.preventDefault();

        let errorList=[];

        const idToSave = this.state.entry.bucketName;
        if(idToSave===null || idToSave===""){
            errorList.concat(["You must specify a valid bucket name"]);
        }

        if(errorList.length>0){
            this.setState({formValidationErrors: errorList});
            return;
        }

        let entryToSend = this.state.entry;
        if(entryToSend.lastError==="") entryToSend.lastError = null;

        this.setState({loading: true}, ()=>axios.post("/api/scanTarget",entryToSend)
            .then(result=> {
                    console.log("Saved result successfully");
                    this.setState({loading: false, completed: true});
                })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, error: err});
            })
        );
    }

    triggerAddedScan(){
        return this.generalScanTrigger("additionScan")
    }
    triggerRemovedScan(){
        return this.generalScanTrigger("deletionScan")
    }
    triggerFullScan(){
        return this.generalScanTrigger("scan")
    }

    triggerValidateConfig(){
        const targetId = this.state.idToLoad;
        this.setState({loading: true, currentActionCaption: "Validating config..."}, ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "checkTranscoder")
            .then(result=>{
                console.log("Config validation has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null}, ()=>window.setTimeout(this.updatePendingJobs, 500));
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    triggerTranscodeSetup(){
        const targetId = this.state.idToLoad;
        this.setState({loading:true, currentActionCaption: "Starting setup..."}, ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "createPipelines?force=true")
            .then(result=>{
                console.log("Transcode setup has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null}, ()=>window.setTimeout(this.updatePendingJobs, 500));
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    triggerProxyGen(){
        const targetId = this.state.idToLoad;
        this.setState({loading: true, currentActionCaption: "Starting proxy generation..."},()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "genProxies")
            .then(result=>{
                console.log("Proxy generation has been triggered");
                this.setState({loading:false, lastError:null, scanTargets:[],currentActionCaption: null}, ()=>window.setTimeout(this.updatePendingJobs, 500));
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err,currentActionCaption: null});
            }))
    }

    /**
     * FIXME: this does not belong in this UI location.
     * @param targetId
     */
    triggerProxyRelink(){
        const targetId = this.state.idToLoad;
        this.setState({loading:true}, ()=>axios.post("/api/proxy/relink/global")
            .then(result=>{
                console.log("Global proxy relink has been triggered");
                this.setState({loading: false, lastError:null}, ()=>window.setTimeout(this.updatePendingJobs, 500));
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError:err});
            }))
    }

    generalScanTrigger(type){
        const targetId = this.state.idToLoad;
        console.log(this.state);
        console.log(targetId);
        this.setState({loading: true,currentActionCaption: "Starting scan..."},()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + type)
            .then(result=>{
                console.log("Manual rescan has been triggered");
                window.setTimeout(this.updatePendingJobs, 500);
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    render(){
        if(this.state.completed) return <Redirect to="/admin/scanTargets"/>;
        return <form onSubmit={this.formSubmit}>
            <ReactTooltip/>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <h2>Edit scan target <img src="/assets/images/Spinner-1s-44px.svg"
                                      alt="loading" style={{display: this.state.loading ? "inline" : "none"}}
                                      className="inline-throbber"
            /></h2>
            <div className="centered" style={{display: this.state.formValidationErrors.length>0 ? "block" : "none"}}>
                <ul className="form-errors">
                    {
                        this.state.formValidationErrors.map(entry=><li className="form-errors error-text">{entry}</li>)
                    }
                </ul>
            </div>
            <table className="table">
                <tbody>
                <tr>
                    <td>Bucket name</td>
                    <td><input value={this.state.entry.bucketName} onChange={this.updateBucketname} style={{width:"95%"}}/></td>
                </tr>
                <tr>
                    <td>Proxy bucket</td>
                    <td><input value={this.state.entry.proxyBucket} onChange={this.updateProxyBucket} style={{width: "95%"}}/></td>
                </tr>
                <tr>
                    <td>Region</td>
                    <td><RegionSelector value={this.state.entry.region} onChange={this.updateRegion}/></td>
                </tr>
                <tr>
                    <td>Enabled</td>
                    <td><input type="checkbox" checked={this.state.entry.enabled} onChange={this.toggleEnabled}/></td>
                </tr>
                <tr>
                    <td>Last Scanned</td>
                    <td><TimestampFormatter relative={true} value={this.state.entry.lastScanned}/></td>
                </tr>
                <tr>
                    <td>Scan Interval</td>
                    <td><TimeIntervalComponent editable={true} value={this.state.entry.scanInterval} didUpdate={this.timeIntervalUpdate}/></td>
                </tr>
                <tr>
                    <td>Last Error</td>
                    <td><textarea contentEditable={false} readOnly={true} value={this.state.entry.lastError ? this.state.entry.lastError : ""} style={{width: "85%", verticalAlign:"middle"}}/>
                        <button type="button" onClick={this.clearErrorLog} style={{marginLeft: "0.5em", verticalAlign: "middle"}}>Clear</button></td>
                </tr>
                <tr>
                    <td>Paranoid Mode</td>
                    <td><input type="checkbox" checked={this.state.entry.paranoid} onChange={this.toggleParanoid}/></td>
                </tr>
                <tr>
                    <td>Transcode Setup</td>
                    <td>
                        <span data-tip="Validate transcode config"><FontAwesomeIcon icon="bug" className="clickable button-row" onClick={()=>this.triggerValidateConfig()}/></span>
                        <FontAwesomeIcon icon="industry" className="clickable button-row" data-tip="(Redo) Transcode Setup" onClick={()=>this.triggerTranscodeSetup()}/>
                        {
                        this.state.entry.transcoderCheck ?
                            <TranscoderCheckComponent status={this.state.entry.transcoderCheck.status} checkedAt={this.state.entry.transcoderCheck.checkedAt} log={this.state.entry.transcoderCheck.log}/> :
                            <p className="information">Not checked</p>
                        }
                    </td>
                </tr>
                </tbody>
            </table>
            <h3>Actions</h3>
            <ul className="no-bullets">
                <li className="list-grid">
                    <span data-tip="Addition scan"><FontAwesomeIcon icon="folder-plus" className="clickable button-row" onClick={()=>this.triggerAddedScan()}/>Scan for added files only</span>
                </li>
                <li className="list-grid">
                    <span data-tip="Removal scan"><FontAwesomeIcon icon="folder-minus" className="clickable button-row" onClick={()=>this.triggerRemovedScan()}/>Scan for removed files</span>
                </li>
                <li className="list-grid">
                    <span data-tip="Full scan"><FontAwesomeIcon icon="folder" className="clickable button-row " onClick={()=>this.triggerFullScan()}/>Scan for added and removed files</span>
                </li>
                <li className="list-grid">
                    <span data-tip="Proxy generation"><FontAwesomeIcon icon="compress-arrows-alt" className="clickable button-row" onClick={()=>this.triggerProxyGen()}/>Generate proxies</span>
                </li>
                <li className="list-grid">
                    <span data-tip="Relink proxies"><FontAwesomeIcon icon="book-reader" className="clickable button-row" onClick={()=>this.triggerProxyRelink()}/>Relink existing proxies</span>
                </li>
            </ul>
            <h3>Pending jobs</h3>
            <ul className="no-bullets">
                {
                    this.state.pendingJobIds ?
                        this.state.pendingJobIds.map(jobId=><li key={jobId}><JobEntry jobId={jobId}/></li>) : <li><i>no pending job ids</i></li>
                }
            </ul>
            <input type="submit" value="Save"/>
            <button type="button" onClick={()=>window.location="/admin/scanTargets"}>Back</button>
            {this.state.error ? <ErrorViewComponent error={this.state.error}/> : <span/>}
        </form>
    }
}

export default ScanTargetEdit;