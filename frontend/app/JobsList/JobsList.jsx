import React from 'react';
import axios from 'axios';
import ReactTable from 'react-table';
import { ReactTableDefaults } from 'react-table';
import 'react-table/react-table.css'

import TimestampFormatter from '../common/TimestampFormatter.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import {Link} from "react-router-dom";
import ReactTooltip from "react-tooltip";
import JobTypeIcon from "./JobTypeIcon.jsx";
import JobStatusIcon from "./JobStatusIcon.jsx";
import FilterButton from "../common/FilterButton.jsx";
import omit from "lodash.omit";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import Dialog from 'react-dialog';
import JobsFilterComponent from "./JobsFilterComponent.jsx";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import ResubmitComponent from "./ResubmitComponent.jsx";

class JobsList extends  React.Component {
    constructor(props){
        super(props);

        this.state = {
            jobsList: [],
            loading: false,
            showRelativeTimes: true,
            lastError: null,
            activeFilters: {},
            showingLog: false,
            logContent: "",
            specificJob: null,
            notesBanner: ""
        };

        this.filterUpdated = this.filterUpdated.bind(this);
        this.filterbarUpdated = this.filterbarUpdated.bind(this);
        this.handleModalClose = this.handleModalClose.bind(this);

        this.columns = [
            {
                Header: "ID",
                accessor: "jobId",
                headerProps: {className: "dashboardheader"},
                Cell: props=><p className="small">{props.value}</p>
            },
            {
                Header: "Type",
                accessor: "jobType",
                headerProps: {className: "dashboardheader"},
                Cell: props=><span>
                        <FilterButton fieldName="jobType" values={props.value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="jobType" values={props.value} type="minus" onActivate={this.filterUpdated}/>
                        <JobTypeIcon jobType={props.value}/>
                    </span>
            },
            {
                Header: "Start time",
                accessor: "startedAt",
                defaultSorting: "desc",
                headerProps: {className: "dashboardheader"},
                Cell: props=><TimestampFormatter relative={this.state.showRelativeTimes} value={props.value}/>
            },
            {
                Header: "Completion time",
                accessor: "completedAt",
                headerProps: {className: "dashboardheader"},
                Cell: props=><TimestampFormatter relative={this.state.showRelativeTimes} value={props.value}/>
            },
            {
                Header: "Status",
                accessor: "jobStatus",
                headerProps: {className: "dashboardheader"},
                Cell: props=><span>
                        <FilterButton fieldName="jobStatus" values={props.value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="jobStatus" values={props.value} type="minus" onActivate={this.filterUpdated}/>
                        <JobStatusIcon status={props.value}/>
                </span>
            },
            {
                Header: "Log",
                accessor: "log",
                headerProps: {className: "dashboardheader"},
                Cell: props=> (!props.value || props.value==="") ? <p>None</p> : <a style={{cursor: "pointer"}} onClick={()=>this.setState({logContent: props.value, showingLog: true})}>View</a>
            },
            {
                Header: "Resubmit",
                accessor: "jobId",
                headerProps: {className: "dashboardheader"},
                Cell: props=><ResubmitComponent jobId={props.value}/>
            },
            {
                Header: "Source file",
                accessor: "sourceId",
                headerProps: {className: "dashboardheader"},
                Cell: props=><span>
                        <FilterButton fieldName="sourceId" values={props.value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="sourceId" values={props.value} type="minus" onActivate={this.filterUpdated}/>
                        <Link to={"/browse?open="+props.value}>View</Link>
                </span>
            },
            {
                Header: "Source type",
                accessor: "sourceType",
                headerProps: {className: "dashboardheader"}
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

    /**
     * updates the given job in the job list
     * @param jobToUpdate
     * @returns {Promise} promise that resolves once the update has completed
     */
    updateJobList(jobToUpdate){
        return new Promise((resolve,reject)=>{
            try {
                const itemIndex = this.state.jobsList.findIndex(job => job.jobId === jobToUpdate.jobId);
                this.setState({
                    jobsList: this.state.jobsList.slice(0, itemIndex).concat([jobToUpdate]).concat(this.state.jobsList.slice(itemIndex + 1))
                }, () => resolve());
            } catch(ex){
                reject(ex);
            }
        })
    }

    /**
     * ask the server to update the job info about the given job. Currently only works for transcode jobs.
     * @param jobId
     */
    refreshJob(jobId){
        this.setState({loading: true}, ()=>axios.put("/api/job/transcode/" + jobId + "/refresh").then(response=>{
            console.log("Job update request worked");
            window.setTimeout(()=>{
                axios.get("/api/job/" + jobId).then(response=>{
                    this.updateJobList(response.data.entry).then(()=>{
                        this.setState({loading: false});
                    }).catch(err=>{
                        this.setState({loading:false, lastError: err});
                    })
                })
            }, 1000)
        }).catch(err=>{
            console.error(err);
            axios.get("/api/job/" + jobId).then(response=>{
                this.updateJobList(response.data.entry).then(()=>{
                    this.setState({loading: false});
                }).catch(err=>{
                    this.setState({loading:false, lastError: err});
                })
            });
        }))
    }

    /**
     * called by FilterButton when the filter status changes
     * @param fieldName field to updated-  provided when setting up the filter button
     * @param values value to add/remove - provided when setting up the filter button
     * @param type whether to add (plus) or remove (minus)
     */
    filterUpdated(fieldName, values, type){
        switch (type) {
            case "plus":
                let toUpdate = {};
                toUpdate[fieldName] = values;
                this.setState({
                    activeFilters: Object.assign({}, this.state.activeFilters, toUpdate)
                }, ()=>this.refreshData());
                break;
            case "minus":
                this.setState({
                    activeFilters: omit(this.state.activeFilters, fieldName)
                }, ()=>this.refreshData());
                break;
            default:
                console.error("expected plus or minus in filterUpdate, got ", type);
        }
    }

    filterbarUpdated(newFilters){
        console.log("Filter bar updated: ", newFilters);
        this.setState({activeFilters: newFilters}, ()=>this.refreshData());
    }

    makeUpdateRequest(){
        return Object.keys(this.state.activeFilters).length === 0 ?
            axios.get("/api/job/all") : axios.put("/api/job/search",this.state.activeFilters)
    }

    refreshData(){
        if(this.state.specificJob){
            this.setState({lastError: null, loading: true}, ()=>axios.get("/api/job/" + this.state.specificJob).then(result=>
                this.setState({lastError: null, jobsList: [result.data.entry], loading: false})
            ).catch(err=>{
                console.error(err);
                this.setState({lastError: err, loading: false});
            }))
        } else {
            this.setState({lastError: null, loading: true}, () => this.makeUpdateRequest().then(result => {
                this.setState({lastError: null, jobsList: result.data.entries, loading: false})
            }).catch(err => {
                console.error(err);
                this.setState({loading: false, lastError: err});
            }))
        }
    }

    componentDidUpdate(oldProps, oldState){
        /*if the url changes, update the data */
        if(oldProps.match!==this.props.match){
            this.setState({specificJob: this.props.match.params.hasOwnProperty("jobid") ? this.props.match.params.jobid : null},()=>
            this.refreshData());
        }
    }

    componentWillMount(){
        this.setState(
            {jobsList:[], specificJob: this.props.match.params.hasOwnProperty("jobid") ? this.props.match.params.jobid : null},
            ()=>this.refreshData()
        );
    }

    handleModalClose(){
        this.setState({showingLog: false});
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <JobsFilterComponent activeFilters={this.state.activeFilters} filterChanged={this.filterbarUpdated}/>
            <LoadingThrobber show={this.state.loading} caption="Loading data..." small={true}/>
            {
                this.state.showingLog && <Dialog modal={true}
                                                 title="Job log"
                                                 onClose={this.handleModalClose}
                                                 closeOnEscape={true}
                                                 hasCloseIcon={true}
                                                 isDraggable={true}
                                                 position={{x: window.innerWidth/2-250, y:window.innerHeight}}
                                                 buttons={
                                                     [{
                                                         text: "Close",
                                                         onClick: ()=>this.handleModalClose()
                                                     }]
                                                 }
                >
                    <div style={{height:"200px", overflowY: "auto"}}>{this.state.logContent.split("\n").map(para=><p className="centered longlines">{para}</p>)}</div>
                </Dialog>
            }
            <ReactTable
            data={this.state.jobsList}
            columns={this.columns}
            column={Object.assign({}, ReactTableDefaults.column, {headerClassName: 'dashboardheader'})}
            // style={this.style}
            // iconStyle={this.iconStyle}
            // tableProps={ {className: "dashboardpanel"} }
        />
            <ReactTooltip id="jobslist-tooltip"/>
        </div>
    }
}

export default JobsList;
