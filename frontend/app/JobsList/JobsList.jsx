import React from 'react';
import axios from 'axios';
import SortableTable from 'react-sortable-table';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome';
import TimeIntervalComponent from '../common/TimeIntervalComponent.jsx';
import TimestampFormatter from '../common/TimestampFormatter.jsx';
import ErrorViewComponent from '../common/ErrorViewComponent.jsx';
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import {Link} from "react-router-dom";
import ReactTooltip from "react-tooltip";
import JobTypeIcon from "./JobTypeIcon.jsx";
import JobStatusIcon from "./JobStatusIcon.jsx";

class JobsList extends  React.Component {
    constructor(props){
        super(props);

        this.state = {
            jobsList: [],
            loading: false,
            showRelativeTimes: true,
            lastError: null
        };

        this.columns = [
            {
                header: "ID",
                key: "jobId",
                headerProps: {className: "dashboardheader"}
            },
            {
                header: "Type",
                key: "jobType",
                headerProps: {className: "dashboardheader"},
                render: (value)=><JobTypeIcon jobType={value}/>
            },
            {
                header: "Start time",
                key: "startedAt",
                defaultSorting: "desc",
                headerProps: {className: "dashboardheader"},
                render: value=><TimestampFormatter relative={this.state.showRelativeTimes} value={value}/>
            },
            {
                header: "Completion time",
                key: "completedAt",
                headerProps: {className: "dashboardheader"},
                render: value=><TimestampFormatter relative={this.state.showRelativeTimes} value={value}/>
            },
            {
                header: "Status",
                key: "jobStatus",
                headerProps: {className: "dashboardheader"},
                render: value=><JobStatusIcon status={value}/>
            },
            {
                header: "Log",
                key: "log",
                headerProps: {className: "dashboardheader"},
                render: value=> value==="" ? <a href="#" onClick={()=>this.showLog(value)}>View</a> : <p>None</p>
            },
            {
                header: "Source file",
                key: "sourceId",
                headerProps: {className: "dashboardheader"},
                render: value=><Link to={"/browse?open="+value}>View</Link>
            },
            {
                header: "Source type",
                key: "sourceType",
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

    componentWillMount(){
        this.setState({lastError:null, jobsList:[], loading: true}, ()=>axios.get("/api/job/all").then(result=>{
            this.setState({lastError:null, jobsList: result.data.entries, loading: false})
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err});
        }))
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <SortableTable
            data={this.state.jobsList}
            columns={this.columns}
            style={this.style}
            iconStyle={this.iconStyle}
            tableProps={ {className: "dashboardpanel"} }
        />
            <ReactTooltip id="jobslist-tooltip"/>
        </div>
    }
}

export default JobsList;
