import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import ReactTable from 'react-table';
import { ReactTableDefaults } from 'react-table';
import 'react-table/react-table.css'

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
                Header: "Delete",
                accessor: "bucketName",
                Cell: props=><FontAwesomeIcon icon="trash-alt" className="inline-icon clickable" onClick={()=>this.deleteClicked(props.value)}/>
            },
            {
                Header: "Bucket",
                accessor: "bucketName",
                defaultSorting: "desc",
                Cell: props=><Link to={"scanTargets/" + props.value}>{props.value}</Link>
            },
            {
                Header: "Region",
                accessor: "region",
            },
            {
                Header: "Enabled",
                accessor: "enabled",
                Cell: props=> props.value ? <span><FontAwesomeIcon icon="check-circle" className="inline-icon" style={{color:"green"}}/>yes</span> : <span><FontAwesomeIcon icon="times-circle" className="far inline-icon" style={{color: "darkred"}}/>no</span>
            },
            {
                Header: "Last Scan",
                accessor: "lastScanned",
                Cell: props=><TimestampFormatter relative={true} value={props.value}/>
            },
            {
                Header: "Scan Interval",
                accessor: "scanInterval",
                Cell: props=><TimeIntervalComponent editable={false} value={props.value}/>
            },
            {
                Header: "Currently scanning",
                accessor: "scanInProgress",
                Cell: props=> props.value ? "yes" : "no"
            },
            {
                Header: "Last scan error",
                accessor: "lastError",
                Cell: props=>props.value ? props.value : "-"
            },
            {
                Header: "Proxy Bucket",
                accessor: "proxyBucket",
            },
            {
                Header: "Transcoder check",
                accessor: "transcoderCheck",
                Cell: props=> props.value ? <TranscoderCheckComponent status={props.value.status} checkedAt={props.value.checkedAt} log={props.value.log}/> : <p className="information">Not checked</p>
            },
            {
                Header: "Pending jobs",
                accessor: "pendingJobIds",
                Cell: props=> props.value && props.value.length>0 ? props.value.length : <p className="information">none</p>
            } /*,
            {
                Header: "Trigger",
                accessor: "bucketName",
                Cell: value=><span>
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
            }*/
        ];

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
            <ReactTooltip/>
            <BreadcrumbComponent path={this.props.location ? this.props.location.pathname : "/unknown"}/>
            <div id="right-button-holder" style={{float: "right"}}>
                <button type="button" onClick={this.newButtonClicked}>New</button>
            </div>
            <div>
                <LoadingThrobber show={this.state.loading} small={true} caption={this.state.currentActionCaption ? this.state.currentActionCaption : "Loading..."}/>
                <ErrorViewComponent error={this.state.lastError}/>
            </div>
            <ReactTable
            data={this.state.scanTargets}
            columns={this.columns}
            column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
            pageSize={5}
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
        </div>
    }
}

export default ScanTargetsList;