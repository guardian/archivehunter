import React from 'react';
import PropTypes from 'prop-types';
import axios from 'axios';
import Expander from '../common/Expander.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import JobStatusIcon from '../JobsList/JobStatusIcon.jsx';
import {Link} from "react-router-dom";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import {handle419} from "../common/Handle419.jsx";

class EntryJobs extends React.Component {
    static propTypes = {
        entryId: PropTypes.string.isRequired,
        loadImmediate: PropTypes.bool.isRequired,
        autoRefresh: PropTypes.bool.isRequired,
        autoRefreshUpdated: PropTypes.func.isRequired
    };

    constructor(props){
       super(props);

       this.state = {
           loading: false,
           lastError: null,
           jobsList: [],
           expanded: false,
           refreshTimer: null
       };

       this.expanderChanged = this.expanderChanged.bind(this);
       this.autoRefresh = this.autoRefresh.bind(this);
    }

    loadData(){
        this.setState({loading: true, lastError: null}, ()=>axios.get("/api/job/forFile/" + this.props.entryId).then(response=>{
            this.setState({loading: false, lastError: null, jobsList: response.data.entries})
        }).catch(err=>{
            console.error(err);
            handle419(err).then(didRefresh=>{
                if(didRefresh){
                    console.log("refresh succeeded");
                    //now retry the call
                    this.loadData();
                } else {
                    this.setState({loading: false, lastError: err, jobsList: []});
                }
            }).catch(err=>{
                console.error("Retry failed");
                alert(err.toString());
                window.location.reload(true);
            })
        }))
    }

    componentWillMount(){
        if(this.props.loadImmediate) this.loadData();
    }

    componentDidUpdate(oldProps, oldState) {
        if (this.props.entryId !== oldProps.entryId) this.setState({jobsList: []}, () => this.loadData());
        if(oldProps.autoRefresh !== this.props.autoRefresh){
            if(this.state.refreshTimer) window.clearInterval(this.state.refreshTimer);
            if(this.props.autoRefresh){
                this.setState({refreshTimer: window.setInterval(this.autoRefresh, 3000)})
            }
        }
    }

    autoRefresh(){
        console.log("autoRefresh");
        this.loadData();
    }

    expanderChanged(newState){
        this.setState({expanded: newState}, ()=>{if(!this.props.loadImmediate) this.loadData()});
    }

    renderJobsList(){
        return this.state.jobsList.length===0 && !this.state.loading ? <p className="informative">No jobs</p> : <ul className="job-list">
            {
                this.state.jobsList.map(entry => <li className="job-list-entry">
                    <Link to={"/admin/jobs/" + entry.jobId}>
                        <JobStatusIcon status={entry.jobStatus}/><span style={{marginLeft: "0.4em"}}>{entry.jobType}</span>
                    </Link>
                </li>)
            }
        </ul>
    }

    render(){
        return <div className="entry-jobs-list">
            <span>
                <Expander expanded={this.state.expanded} onChange={this.expanderChanged}/>
                <span style={{marginLeft: "0.4em"}}>Jobs for this item</span>
                <img src="/assets/images/Spinner-1s-44px.svg" style={{display: this.state.loading ? "inline-block":"none", verticalAlign: "bottom", height: "1.9em"}}/>
            </span>
            <div style={{display: this.state.expanded ? "block" : "none"}}>
                <span className="command-link">
                    <a onClick={(evt)=>this.props.autoRefreshUpdated(!this.props.autoRefresh)}>Auto-refresh</a>
                    <FontAwesomeIcon icon="check" style={{display: this.props.autoRefresh ? "inline" : "none", marginLeft: "0.5em"}}/>
                </span>
                {
                    this.state.lastError ? <ErrorViewComponent error={this.state.lastError}/> : this.renderJobsList()
                }
            </div>
        </div>
    }
}

export default EntryJobs;