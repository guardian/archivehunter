import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import axios from 'axios';
import {Redirect} from 'react-router-dom';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import RegionSelector from "../common/RegionSelector.jsx";

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
            lastError: ""
        };

        this.state = {
            entry: defaultValues,
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

        this.formSubmit = this.formSubmit.bind(this);
    }

    loadData(idToLoad){
        this.setState({error:null, loading:true},
            ()=>axios.get("/api/scanTarget/" + encodeURIComponent(idToLoad))
                .then(response=> {
                    this.setState({loading: false, error: null, entry: response.data.entry})
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
            this.loadData(idToLoad)
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

    render(){
        if(this.state.completed) return <Redirect to="/admin/scanTargets"/>;
        return <form onSubmit={this.formSubmit}>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <h2>Edit scan target <img src="/assets/images/Spinner-1s-44px.svg" style={{display: this.state.loading ? "inline" : "none"}}/></h2>
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
                </tbody>
            </table>
            <input type="submit" value="Save"/>
            <button type="button" onClick={()=>window.location="/admin/scanTargets"}>Back</button>
            {this.state.error ? <ErrorViewComponent error={this.state.error}/> : <span/>}
        </form>
    }
}

export default ScanTargetEdit;