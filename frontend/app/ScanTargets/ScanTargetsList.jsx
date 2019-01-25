import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import SortableTable from 'react-sortable-table';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import { Link } from 'react-router-dom';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import Dialog from 'react-dialog';
import ReactTooltip from 'react-tooltip'
import TranscoderCheckComponent from "./TranscoderCheckComponent.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import JobEntry from "../common/JobEntry.jsx";

class ScanTargetsList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            scanTargets: [],
            showingDeleteConfirm: false,
            deletionTarget:null,
            currentActionCaption: null
        };

        /*
        bucketName:String, enabled:Boolean,
         lastScanned:Option[ZonedDateTime],
         scanInterval:Long, scanInProgress:Boolean, lastError:Option[String])

         */
        this.deleteClicked = this.deleteClicked.bind(this);
        this.newButtonClicked = this.newButtonClicked.bind(this);

        this.columns = [
            {
                header: "Delete",
                key: "bucketName",
                headerProps: {className: "dashboardheader"},
                render: value=><FontAwesomeIcon icon="trash-alt" className="inline-icon clickable" onClick={()=>this.deleteClicked(value)}/>
            },
            {
                header: "Bucket",
                key: "bucketName",
                defaultSorting: "desc",
                headerProps: {className: "dashboardheader"},
                render: value=><Link to={"scanTargets/" + value}>{value}</Link>
            },
            {
                header: "Region",
                key: "region",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Enabled",
                key: "enabled",
                headerProps: {className: "dashboardheader"},
                render: value=> value ? <span><FontAwesomeIcon icon="check-circle" className="inline-icon" style={{color:"green"}}/>yes</span> : <span><FontAwesomeIcon icon="times-circle" className="far inline-icon" style={{color: "darkred"}}/>no</span>
            },
            {
                header: "Last Scan",
                key: "lastScanned",
                headerProps: {className: "dashboardheader"},
                render: value=><TimestampFormatter relative={true} value={value}/>
            },
            {
                header: "Scan Interval",
                key: "scanInterval",
                headerProps: {className: "dashboardheader"},
                render: value=><TimeIntervalComponent editable={false} value={value}/>
            },
            {
                header: "Currently scanning",
                key: "scanInProgress",
                headerProps: {className: "dashboardheader"},
                render: value=> value ? "yes" : "no"
            },
            {
                header: "Last scan error",
                key: "lastError",
                headerProps: {className: "dashboardheader"},
                render: value=>value ? value : "-"
            },
            {
                header: "Proxy Bucket",
                key: "proxyBucket",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Transcoder check",
                headerProps: {className: "dashboardheader"},
                key: "transcoderCheck",
                render: value=> value ? <TranscoderCheckComponent status={value.status} checkedAt={value.checkedAt} log={value.log}/> : <p className="information">Not checked</p>
            },
            {
                header: "Pending jobs",
                headerProps: {className: "dashboardheader"},
                key: "pendingJobIds",
                render: value=> value ? <ul className="jobs-list">{
                    value.map(entry=><li key={entry}><JobEntry jobId={entry}/></li>)
                }</ul> : <ul className="jobs-list"/>
            },
            {
                header: "Trigger",
                key: "bucketName",
                headerProps: {className: "dashboardheader"},
                render: value=><span>
                    <span data-tip="Addition scan"><FontAwesomeIcon icon="folder-plus" className="clickable button-row" onClick={()=>this.triggerAddedScan(value)}/></span>
                    <span data-tip="Removal scan"><FontAwesomeIcon icon="folder-minus" className="clickable button-row" onClick={()=>this.triggerRemovedScan(value)}/></span>
                    <span data-tip="Full scan"><FontAwesomeIcon icon="folder" className="clickable button-row " onClick={()=>this.triggerFullScan(value)}/></span>
                    <br/>
                    <span data-tip="Proxy generation"><FontAwesomeIcon icon="compress-arrows-alt" className="clickable button-row" onClick={()=>this.triggerProxyGen(value)}/></span>
                    <span data-tip="Relink proxies"><FontAwesomeIcon icon="book-reader" className="clickable button-row" onClick={()=>this.triggerProxyRelink(value)}/></span>
                    <br/>
                    <span data-tip="Validate transcode config"><FontAwesomeIcon icon="bug" className="clickable button-row" onClick={()=>this.triggerValidateConfig(value)}/></span>
                    <FontAwesomeIcon icon="industry" className="clickable button-row" data-tip="(Redo) Transcode Setup" onClick={()=>this.triggerTranscodeSetup(value)}/>
                </span>
            }
        ];
        this.style = {
            backgroundColor: '#eee',
            border: '1px solid black',
            borderCollapse: 'collapse'
        };

        this.iconStyle = {
            color: '#aaa',
            paddingLeft: '5px',
            paddingRight: '5px'
        };

    }

    triggerAddedScan(targetId){
        return this.generalScanTrigger(targetId,"additionScan")
    }
    triggerRemovedScan(targetId){
        return this.generalScanTrigger(targetId,"deletionScan")
    }
    triggerFullScan(targetId){
        return this.generalScanTrigger(targetId,"scan")
    }

    triggerValidateConfig(targetId){
        this.setState({loading: true, currentActionCaption: "Validating config..."}, ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "checkTranscoder")
            .then(result=>{
                console.log("Config validation has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null});
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    triggerTranscodeSetup(targetId){
        this.setState({loading:true, currentActionCaption: "Starting setup..."}, ()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "createPipelines?force=true")
            .then(result=>{
                console.log("Transcode setup has been started with job ID " + result.data.entity);
                this.setState({loading: false, lastError: null, currentActionCaption: null});
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    triggerProxyGen(targetId){
        this.setState({loading: true, currentActionCaption: "Starting proxy generation..."},()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + "genProxies")
            .then(result=>{
                console.log("Proxy generation has been triggered");
                this.setState({loading:false, lastError:null, scanTargets:[],currentActionCaption: null}, ()=>setTimeout(()=>this.componentWillMount(),1000));
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
    triggerProxyRelink(targetId){
        this.setState({loading:true}, ()=>axios.post("/api/proxy/relink/global")
            .then(result=>{
                console.log("Global proxy relink has been triggered");
                this.setState({loading: false, lastError:null});
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError:err});
            }))
    }

    generalScanTrigger(targetId,type){
        this.setState({loading: true,currentActionCaption: "Starting scan..."},()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + type)
            .then(result=>{
                console.log("Manual rescan has been triggered");
                this.setState({lastError:null,currentActionCaption: "Refreshing..."}, ()=>setTimeout(()=>this.componentWillMount(),1000));
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err, currentActionCaption: null});
            }))
    }

    deleteClicked(targetId){
        this.setState({deletionTarget: targetId, showingDeleteConfirm: true});
    }

    componentWillMount(){
        this.setState({loading: true, currentActionCaption: null}, ()=>axios.get("/api/scanTarget").then(response=>{
            this.setState({loading: false, lastError: null, scanTargets: response.data.entries});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }));
    }

    handleModalClose(){
        this.setState({deletionTarget:null, showingDeleteConfirm: false});
    }

    doDelete(){
        this.setState({loading: true, currentActionCaption: "Deleting..."}, ()=>axios.delete("/api/scanTarget/" + this.state.deletionTarget)
            .then(response=>{
                console.log("Delete request successful");
                this.setState({loading: false, deletionTarget: null, showingDeleteConfirm: false}, ()=>this.componentWillMount())
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, deletionTarget: null, showingDeleteConfirm: false, lastError: err});
            }))
    }

    newButtonClicked(){
        this.props.history.push('/admin/scanTargets/new');
    }

    render(){
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <div id="right-button-holder" style={{float: "right"}}>
                <button type="button" onClick={this.newButtonClicked}>New</button>
            </div>
            <div>
                <LoadingThrobber show={this.state.loading} small={true} caption={this.state.currentActionCaption ? this.state.currentActionCaption : "Loading..."}/>
                <ErrorViewComponent error={this.state.lastError}/>
            </div>
            <SortableTable
            data={this.state.scanTargets}
            columns={this.columns}
            style={this.style}
            iconStyle={this.iconStyle}
            tableProps={ {className: "dashboardpanel"} }
        />
            {
                this.state.showingDeleteConfirm && <Dialog modal={true}
                                                           title="Delete Scan Target"
                                                           onClose={this.handleModalClose}
                                                           closeOnEscape={true}
                                                           hasCloseIcon={true}
                                                           isDraggable={true}
                                                           position={{x: window.innerWidth/2-250, y:window.innerHeight}}
                                                           buttons={
                                                               [{
                                                                   text: "Cancel",
                                                                   onClick: ()=>this.handleModalClose()
                                                               },{
                                                                   text: "Delete",
                                                                   onClick: ()=>this.doDelete()
                                                               }
                                                               ]
                                                           }
                >
                    <p style={{height:"200px"}} className="centered">Are you sure you want to delete the scan target for {this.state.deletionTarget}?</p>
                </Dialog>
            }
            <ReactTooltip/>
        </div>
    }
}

export default ScanTargetsList;