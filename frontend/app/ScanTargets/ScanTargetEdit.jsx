import React from 'react';
import TimestampFormatter from '../common/TimestampFormatter';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import axios from 'axios';
import {Redirect} from 'react-router-dom';
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import RegionSelector from "../common/RegionSelector.jsx";
import TranscoderCheckComponent from "./TranscoderCheckComponent.jsx";
import JobEntry from "../common/JobEntry.jsx";
import MuiAlert from '@material-ui/lab/Alert';
import {
    Grid,
    Snackbar,
    TextField,
    Typography,
    Button,
    Switch,
    Tooltip,
    Paper,
    createStyles,
    IconButton
} from "@material-ui/core";
import AdminContainer from "../admin/AdminContainer";
import {makeStyles, withStyles} from "@material-ui/core";
import clsx from "clsx";
import {baseStyles} from "../BaseStyles";
import ScanTargetActionsBox from "./ScanTargetActionsBox";
import {Helmet} from "react-helmet";
import {BugReport, Tune} from "@material-ui/icons";

const styles = (theme) => Object.assign(createStyles({
    formContainer: {
        padding: "1em",
        paddingTop: "0.4em",
        marginBottom: "3em"
    },
    formErrors: {
        display: "inline"
    },
    actionButtonsContainer: {
        width: "95%",
        marginLeft: "auto",
        marginRight: "auto"
    }
}, baseStyles));

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
            lastErrorSeverity: "information",
            pendingJobIds: [],
            paranoid: false,
            proxyEnabled: false
        };

        this.state = {
            entry: defaultValues,
            idToLoad: null,
            loading: false,
            error: null,
            formValidationErrors: [],
            completed: false,
            showingAlert: false
        };

        this.updateBucketname = this.updateBucketname.bind(this);
        this.updateProxyBucket = this.updateProxyBucket.bind(this);
        this.updateRegion = this.updateRegion.bind(this);
        this.toggleEnabled = this.toggleEnabled.bind(this);
        this.timeIntervalUpdate = this.timeIntervalUpdate.bind(this);
        this.toggleParanoid = this.toggleParanoid.bind(this);
        this.toggleProxyEnabled = this.toggleProxyEnabled.bind(this);

        this.formSubmit = this.formSubmit.bind(this);

        this.closeAlert = this.closeAlert.bind(this);
        this.updatePendingJobs = this.updatePendingJobs.bind(this);

        this.triggerValidateConfig = this.triggerValidateConfig.bind(this);
        this.triggerTranscodeSetup = this.triggerTranscodeSetup.bind(this);
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
                    this.setState({loading: false, error: null, pendingJobIds: response.data.entry.pendingJobIds ? response.data.entry.pendingJobIds : []})
                })
                .catch(err=>{
                    console.error(err);
                    this.setState({loading: false, error: err, entry: null})
                })
        );
    }

    componentDidMount(){
        const idToLoad = this.props.location.pathname.split('/').slice(-1)[0];
        console.log("going to load from id", idToLoad);

        if(idToLoad!=="new"){
            this.loadData(idToLoad).then(()=>window.setInterval(this.updatePendingJobs,3000));
        } else {

        }
    }

    triggerValidateConfig(){
        const targetId = this.state.idToLoad;
        this.setState({loading: true,  showingAlert: true, lastErrorSeverity: "info", lastError: "Checking transcoder setup, refresh the page in a few seconds"},
            ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "checkTranscoder")
            .then(result=>{
                console.log("Config validation has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null}, );
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, lastErrorSeverity: "error", showingAlert: "true", currentActionCaption: null});
            }))
    }

    triggerTranscodeSetup(){
        const targetId = this.state.idToLoad;
        this.setState({loading:true,
                lastError:"Starting transcoder setup, this can take a minute or two",
                lastErrorSeverity: "info",
                showingAlert: true
            }, ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "createPipelines?force=true")
            .then(result=>{
                console.log("Transcode setup has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null});
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
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

    toggleProxyEnabled(evt){
        const newEntry = Object.assign({}, this.state.entry, {proxyEnabled: !this.state.entry.proxyEnabled});
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

    closeAlert() {
        this.setState({showingAlert: false});
    }

    render(){
        if(this.state.completed) return <Redirect to="/admin/scanTargets"/>;
        return <>
            <Helmet>
                <title>Scan Targets - ArchiveHunter</title>
            </Helmet>
            <Snackbar
                open={this.state.showingAlert}
                autoHideDuration={60000}
                onClose={this.closeAlert}
            >
                <MuiAlert elevation={6} variant="filled" onClose={this.closeAlert} severity={this.state.lastErrorSeverity}>
                    {this.state.lastError}
                </MuiAlert>
            </Snackbar>
            <AdminContainer {...this.props}>

        <form onSubmit={this.formSubmit}>
            <Paper elevation={3} className={this.props.classes.formContainer}>
            <Typography variant="h4" style={{overflow: "hidden"}}>Edit scan target <img src="/assets/images/Spinner-1s-44px.svg"
                                      alt="loading" style={{display: this.state.loading ? "inline" : "none", height:"32px"}}
                                      className={this.props.classes.inlineThrobber}
            /></Typography>
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
                    <td><TextField value={this.state.entry.bucketName} onChange={this.updateBucketname} style={{width:"95%"}}/></td>
                </tr>
                <tr>
                    <td>Proxy bucket</td>
                    <td><TextField value={this.state.entry.proxyBucket} onChange={this.updateProxyBucket} style={{width: "95%"}}/></td>
                </tr>
                <tr>
                    <td>Region</td>
                    <td><RegionSelector value={this.state.entry.region} onChange={this.updateRegion}/></td>
                </tr>
                <tr>
                    <td>Enabled</td>
                    <td><Switch checked={this.state.entry.enabled} onClick={this.toggleEnabled}/></td>
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
                    <td><TextField multiline={true} contentEditable={false} readOnly={true} value={this.state.entry.lastError ? this.state.entry.lastError : ""} style={{width: "85%", verticalAlign:"middle"}}/>
                        <Button onClick={this.clearErrorLog} style={{marginLeft: "0.5em", verticalAlign: "middle"}}>Clear</Button></td>
                </tr>
                <tr>
                    <td>Paranoid Mode</td>
                    <td><Switch checked={this.state.entry.paranoid} onChange={this.toggleParanoid}/></td>
                </tr>
                <tr>
                    <td>Enable proxying</td>
                    <td><Tooltip title="Clearing this will prevent any proxies from being generated">
                        <Switch checked={this.state.entry.proxyEnabled}
                        onChange={this.toggleProxyEnabled}
                        style={{marginRight: "0.2em"}}
                        data-tip="Clearing this will prevent any proxies from being generated"
                        />
                    </Tooltip>
                        </td>
                </tr>
                <tr>
                    <td style={{verticalAlign: "top"}}>Transcode Setup</td>
                    <td>
                        <Tooltip title="Validate transcode config">
                            <IconButton onClick={this.triggerValidateConfig}><BugReport/></IconButton>
                        </Tooltip>
                        <Tooltip title="(Redo) Transcode Setup">
                            <IconButton onClick={this.triggerTranscodeSetup}><Tune/></IconButton>
                        </Tooltip>
                        {
                        this.state.entry.transcoderCheck ?
                            <TranscoderCheckComponent status={this.state.entry.transcoderCheck.status} checkedAt={this.state.entry.transcoderCheck.checkedAt} log={this.state.entry.transcoderCheck.log}/> :
                            <Typography variant="caption">No transcoder check has been run</Typography>
                        }
                    </td>
                </tr>
                </tbody>
            </table>
            </Paper>

            <Grid container direction="row" spacing={3}>
                <Grid item xs={6}>
            <ScanTargetActionsBox idToLoad={this.state.idToLoad}
                                  actionDidStart={(msg)=>this.setState({lastErrorSeverity: "info", lastError:msg, showingAlert: true})}
                                  actionDidFail={(msg)=>this.setState({lastErrorSeverity: "error", lastError:msg, showingAlert:true})}
                                  actionDidSucceed={}
                                  classes={this.props.classes}
                                  bucketName={this.state.bucketName}/>
                </Grid>
                <Grid item xs={6}>
                    <Paper elevation={3} className={this.props.classes.formContainer}>
                        <Typography variant="h4">Pending jobs</Typography>
                        <ul className="no-bullets">
                            {
                                this.state.pendingJobIds && this.state.pendingJobIds.length>0 ?
                                    this.state.pendingJobIds.map(jobId=><li key={jobId}><JobEntry jobId={jobId} showLink={true}/></li>) : <li><i>no pending job ids</i></li>
                            }
                        </ul>
                    </Paper>
                </Grid>
            </Grid>
            <Grid container direction="row" spacing={3} style={{width:"100%"}}>
                <Grid item><Button variant="contained" type="submit">Save</Button></Grid>
                <Grid item><Button variant="contained" type="button" onClick={()=>this.props.location.history.back()}>Back</Button></Grid>
            </Grid>
        </form>
            {this.state.error ? <ErrorViewComponent error={this.state.error}/> : <span/>}
        </AdminContainer>
        </>
    }
}

export default withStyles(styles)(ScanTargetEdit);