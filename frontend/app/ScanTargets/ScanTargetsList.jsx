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

class ScanTargetsList extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            lastError: null,
            scanTargets: [],
            showingDeleteConfirm: false,
            deletionTarget:null
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
                header: "Trigger",
                key: "bucketName",
                headerProps: {className: "dashboardheader"},
                render: value=><span>
                    <FontAwesomeIcon icon="folder-plus" className="clickable button-row" onClick={()=>this.triggerAddedScan(value)}/>
                    <FontAwesomeIcon icon="folder-minus" className="clickable button-row" onClick={()=>this.triggerRemovedScan(value)}/>
                    <FontAwesomeIcon icon="folder" className="clickablebutton-row " onClick={()=>this.triggerFullScan(value)}/>
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
    generalScanTrigger(targetId,type){
        this.setState({loading: true},()=>axios.post("/api/scanTarget/" + encodeURIComponent(targetId) + "/" + type)
            .then(result=>{
                console.log("Manual rescan has been triggered");
                this.setState({loading:false, lastError:null, scanTargets:[]}, ()=>setTimeout(()=>this.componentWillMount(),1000));
            })
            .catch(err=>{
                console.error(err);
                this.setState({loading: false, lastError: err});
            }))
    }

    deleteClicked(targetId){
        this.setState({deletionTarget: targetId, showingDeleteConfirm: true});
    }

    componentWillMount(){
        this.setState({loading: true}, ()=>axios.get("/api/scanTarget").then(response=>{
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
        this.setState({loading: true}, ()=>axios.delete("/api/scanTarget/" + this.state.deletionTarget)
            .then(response=>{
                console.log("Delete request successful");
                this.setState({loading: false, deletionTarget: null, showingDeleteConfirm: false, scanTargets:[]}, ()=>this.componentWillMount())
            }).catch(err=>{
                console.error(err);
                this.setState({loading: false, deletionTarget: null, showingDeleteConfirm: false, lastError: err});
            }))
    }

    newButtonClicked(){
        this.props.history.push('/admin/scanTargets/new');
    }

    render(){
        if(this.state.error){
            return <ErrorViewComponent error={this.state.error}/>
        }

        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <div style={{float: "right"}}>
                <button type="button" onClick={this.newButtonClicked}>New</button>
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
                                                           position={{x: window.innerWidth/2-250, y:0}}
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