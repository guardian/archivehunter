import React from 'react';
import axios from 'axios';
import SortableTable from 'react-sortable-table';
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
            logContent: ""
        };

        this.filterUpdated = this.filterUpdated.bind(this);
        this.handleModalClose = this.handleModalClose.bind(this);

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
                render: (value)=><span>
                        <FilterButton fieldName="jobType" values={value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="jobType" values={value} type="minus" onActivate={this.filterUpdated}/>
                        <JobTypeIcon jobType={value}/>
                    </span>
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
                render: value=><span>
                        <FilterButton fieldName="jobStatus" values={value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="jobStatus" values={value} type="minus" onActivate={this.filterUpdated}/>
                        <JobStatusIcon status={value}/>
                </span>
            },
            {
                header: "Log",
                key: "log",
                headerProps: {className: "dashboardheader"},
                render: value=> (!value || value==="") ? <p>None</p> : <a style={{cursor: "pointer"}} onClick={()=>this.setState({logContent: value, showingLog: true})}>View</a>
            },
            {
                header: "Source file",
                key: "sourceId",
                headerProps: {className: "dashboardheader"},
                render: value=><span>
                        <FilterButton fieldName="sourceId" values={value} type="plus" onActivate={this.filterUpdated}/>
                        <FilterButton fieldName="sourceId" values={value} type="minus" onActivate={this.filterUpdated}/>
                        <Link to={"/browse?open="+value}>View</Link>
                </span>
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

    refreshData(){
        this.setState({lastError: null, loading: true},()=>axios.put("/api/job/search",this.state.activeFilters).then(result=>{
            this.setState({lastError: null, jobsList: result.data.entries, loading: false})
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    componentWillMount(){
        this.setState({lastError:null, jobsList:[], loading: true}, ()=>axios.get("/api/job/all").then(result=>{
            this.setState({lastError:null, jobsList: result.data.entries, loading: false})
        }).catch(err=>{
            console.error(err);
            this.setState({lastError: err, loading: false});
        }))
    }

    handleModalClose(){
        this.setState({showingLog: false});
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <LoadingThrobber show={this.state.loading} caption="Loading data..." small={false}/>
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
                    <p style={{height:"200px"}} className="centered">{this.state.logContent}</p>
                </Dialog>
            }
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
