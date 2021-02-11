import React from 'react';
import axios from 'axios';
import {formatError} from '../common/ErrorViewComponent.jsx';
import omit from "lodash.omit";
import JobsFilterComponent from "./JobsFilterComponent.jsx";
import {makeJobsListColumns} from "./JobsListContent";
import AdminContainer from "../admin/AdminContainer";
import {DataGrid} from "@material-ui/data-grid";
import {withStyles, createStyles, Paper, Dialog, DialogTitle, Typography, Snackbar, Theme} from "@material-ui/core";
import MuiAlert from "@material-ui/lab/Alert";
import uuid from "uuid";
import {Helmet} from "react-helmet";

const styles = (theme) => createStyles({
    tableContainer: {
        marginTop: "1em",
        height: "80vh"
    },
    logLine: {
        fontFamily: "Courier, serif",
        color: theme.palette.success.dark,
    },
    silentList: {
        listType: "none"
    }
});

class JobsList extends  React.Component {
    static knownKeys = [
        "jobType",
        "jobStatus",
        "sourceId"
    ];  //we will respond to these parameters on the query string as filters

    constructor(props){
        super(props);

        this.state = {
            jobsList: [],
            loading: false,
            showRelativeTimes: true,
            showingAlert: false,
            lastError: null,
            showMessage: null,
            activeFilters: {
                jobType: "proxy"
            },
            showingLog: false,
            logContent: "",
            specificJob: null
        };

        this.filterUpdated = this.filterUpdated.bind(this);
        this.filterbarUpdated = this.filterbarUpdated.bind(this);
        this.handleModalClose = this.handleModalClose.bind(this);
        this.refreshData = this.refreshData.bind(this);
        this.closeAlert = this.closeAlert.bind(this);
        this.openItemRequested = this.openItemRequested.bind(this);
        this.openLog = this.openLog.bind(this);

        this.subComponentErrored = this.subComponentErrored.bind(this);
        this.resubmitSuccess = this.resubmitSuccess.bind(this);
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
                        this.setState({loading:false, lastError: err, showingAlert: true});
                    })
                })
            }, 1000)
        }).catch(err=>{
            console.error(err);
            axios.get("/api/job/" + jobId).then(response=>{
                this.updateJobList(response.data.entry).then(()=>{
                    this.setState({loading: false});
                }).catch(err=>{
                    this.setState({loading:false, lastError: err, showingAlert: true});
                })
            });
        }))
    }

    componentDidCatch(error, errorInfo) {
        console.error("JobsList failed to load: ", error, errorInfo);
    }

    static getDerivedStateFromError(err) {
        return {
            lastError: "A frontend internal error occurred, please see the browser console for more details",
            showingAlert: true,
            loading: false,

        }
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
                }, ()=>{
                    this.props.history.push(this.queryParamsFromFilters());
                    this.refreshData()
                });
                break;
            case "minus":
                this.setState({
                    activeFilters: omit(this.state.activeFilters, fieldName)
                }, ()=>{
                    this.props.history.push(this.queryParamsFromFilters());
                    this.refreshData()
                });
                break;
            default:
                console.error("expected plus or minus in filterUpdate, got ", type);
        }
    }

    filterbarUpdated(newFilters){
        console.log("Filter bar updated: ", newFilters);
        this.setState({activeFilters: newFilters}, ()=>{
            this.props.history.push(this.queryParamsFromFilters());
            this.refreshData();
        });
    }

    makeUpdateRequest(){
        return Object.keys(this.state.activeFilters).length === 0 ?
            axios.get("/api/job/all") : axios.put("/api/job/search",this.state.activeFilters)
    }

    /**
     * MUI wants an 'id' prop on each record, so let's give it one
     */
    enrichServerEntry(sourceData) {
        if(sourceData.hasOwnProperty("jobId")) {
            return Object.assign(sourceData, {id: sourceData.jobId})
        } else {
            return Object.assign(sourceData, {id: uuid()});
        }
    }
    refreshData(){
        if(this.state.specificJob){
            this.setState({lastError: null, loading: true}, ()=>axios.get("/api/job/" + this.state.specificJob).then(result=>
                this.setState({lastError: null, jobsList: [this.enrichServerEntry(result.data.entry)], loading: false})
            ).catch(err=>{
                console.error(err);
                this.setState({lastError: err, loading: false, showingAlert: true});
            }))
        } else {
            this.setState({lastError: null, loading: true}, () => this.makeUpdateRequest().then(result => {
                this.setState({lastError: null, jobsList: result.data.entries.map(this.enrichServerEntry), loading: false})
            }).catch(err => {
                console.error(err);
                this.setState({loading: false, lastError: err, showingAlert: true});
            }))
        }
    }

    componentDidUpdate(oldProps, oldState, snapshot){
        /*if the url changes, update the data */
        if(oldProps.match!==this.props.match){
            this.setState({specificJob: this.props.match.params.hasOwnProperty("jobid") ? this.props.match.params.jobid : null},()=>
            this.refreshData());
        }
        if(oldState.lastError==null && this.state.lastError!=null) {
            this.setState({
                showingMessage: null
            });
        }
        if(oldState.showingMessage==null && this.state.showingMessage!=null) {
            this.setState({
                lastError: null
            })
        }
    }

    /**
     * converts the current status of filters dict to a query string
     */
    queryParamsFromFilters(){
        return "?" + Object.keys(this.state.activeFilters).map(key=>key + "=" + this.state.activeFilters[key]).join("&");
    }

    /**
     * parses an available query string and extracts relevant filters for intial page state
     */
    filtersFromQueryParams(){
        const parts = this.props.location.search.split('&');

        console.log(parts);
        const breakdown = parts.reduce((acc,entry)=>{
            const kv = entry.split('=');
            const key = kv[0][0] === '?' ? kv[0].substr(1) : kv[0];
            acc[key] = kv[1];
            return acc;
        }, {});

        console.log(breakdown);
        return Object.keys(breakdown)
            .filter(key=>JobsList.knownKeys.includes(key))
            .reduce((acc, key)=>{
                acc[key]=breakdown[key];
                return acc;
            }, {});
    }

    componentDidMount(){
        const qpFilters = this.filtersFromQueryParams();
        console.log(qpFilters);
        const initialFilters = qpFilters.length===0 ? this.state.activeFilters : qpFilters;

        this.setState(
            {
                jobsList:[],
                specificJob: this.props.match.params.hasOwnProperty("jobid") ? this.props.match.params.jobid : null,
                activeFilters: initialFilters
            },
            ()=>this.refreshData()
        );
    }

    handleModalClose(){
        this.setState({showingLog: false});
    }

    closeAlert() {
        this.setState({showingAlert: false});
    }

    openItemRequested(itemRecord) {
        let url = undefined;
        switch(itemRecord.sourceType) {
            case "SRC_MEDIA":
                url = `/browse?open=${encodeURIComponent(itemRecord.sourceId)}`;
                break;
            case "SRC_SCANTARGET":
                url = `/admin/scanTargets/${encodeURIComponent(itemRecord.sourceId)}`;
                break;
            default:
                break;
        }
        if(url) this.props.history.push(url);
    }

    openLog() {
        this.setState({showingLog: true});
    }

    subComponentErrored(errorDesc) {
        this.setState({
            lastError: errorDesc,
            showingAlert: true
        });
    }

    resubmitSuccess() {
        this.setState({
            showMessage: "Job was resubmitted",
            showingAlert: true
        });
    }
    render(){
        const columns = makeJobsListColumns(this.filterUpdated,
            this.openLog,
            this.openItemRequested,
            this.subComponentErrored,
            this.resubmitSuccess,
            this.state.showRelativeTimes);

        return <AdminContainer {...this.props}>
            <Helmet>
                <title>Jobs - ArchiveHunter</title>
            </Helmet>
            <Snackbar open={this.state.showingAlert} onClose={this.closeAlert} autoHideDuration={8000}>
                <>
                {
                    this.state.lastError ? <MuiAlert severity="error" onClose={this.closeAlert}>
                        {typeof(this.state.lastError)==="string" ? this.state.lastError : formatError(this.state.lastError, false)}
                    </MuiAlert> : null
                }
                {
                    this.state.showMessage ? <MuiAlert severity="info" onClose={this.closeAlert}>
                        {this.state.showMessage}
                    </MuiAlert> : null
                }
                </>
            </Snackbar>
            <JobsFilterComponent activeFilters={this.state.activeFilters}
                                 filterChanged={this.filterbarUpdated}
                                 refreshClicked={this.refreshData}
                                 isLoading={this.state.loading}
            />

            <Dialog open={this.state.showingLog} onClose={()=>this.setState({showingLog: false})} aria-labelledby="logs-title">
                <DialogTitle id="logs-title">Logs</DialogTitle>
                <ul className={this.props.classes.silentList}>
                    {
                        this.state.logContent
                            .split("\n")
                            .map((line, idx)=><li key={idx}><Typography className={this.props.classes.logLine}>{line}</Typography></li>)
                    }
                </ul>
            </Dialog>

            <Paper elevation={3} className={this.props.classes.tableContainer}>
                <DataGrid columns={columns} rows={this.state.jobsList}/>
            </Paper>
        </AdminContainer>
    }
}

export default withStyles(styles)(JobsList);
