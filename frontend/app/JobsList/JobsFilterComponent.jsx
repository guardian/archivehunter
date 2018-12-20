import React from 'react';
import PropTypes from 'prop-types';
import omit from 'lodash.omit';

class JobsFilterComponent extends React.Component {
    static propTypes = {
        activeFilters: PropTypes.object.isRequired,
        filterChanged: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);

        this.selectorChanged = this.selectorChanged.bind(this);
    }

    jobTypeFilter(){
        if(this.props.activeFilters.hasOwnProperty("jobType")){
            return this.props.activeFilters.jobType
        } else {
            return "";
        }
    }

    statusFilter(){
        if(this.props.activeFilters.hasOwnProperty("jobStatus")){
            return this.props.activeFilters.jobStatus
        } else {
            return "";
        }
    }

    selectorChanged(evt){
        console.log("Changed", evt.target.getAttribute("id"));
        console.log("new value", evt.target.value);

        switch(evt.target.getAttribute("id")){
            case "jobtype-selector":
                const updatedFilters = evt.target.value!=="" ? Object.assign({}, this.props.activeFilters, {jobType: evt.target.value}) : omit(this.props.activeFilters, "jobType");
                this.props.filterChanged(updatedFilters);
                break;
            case "status-selector":
                const stUpdatedFilters = evt.target.value!=="" ? Object.assign({}, this.props.activeFilters, {jobStatus: evt.target.value}) : omit(this.props.activeFilters, "jobStatus");
                this.props.filterChanged(stUpdatedFilters);
                break;
            default:
                console.error("Got selector changed from unknown source: ", evt.target.getAttribute("id"))
        }
    }

    render() {
        return <div className="filter-bar">
            <span className="filter-entry">
                <label htmlFor="jobtype-selector" className="filter-control-label">Job type</label>
                <select id="jobtype-selector" onChange={this.selectorChanged} value={this.jobTypeFilter()}>
                    <option value="">(any)</option>
                    <option value="proxy">Proxy</option>
                    <option value="thumbnail">Thumbnail</option>
                    <option value="RESTORE">Restore</option>
                </select>
            </span>
            <span className="filter-entry">
                <label htmlFor="status-selector" className="filter-control-label">Status</label>
                <select id="status-selector" onChange={this.selectorChanged} value={this.statusFilter()}>
                    <option value="">(any)</option>
                    <option value="ST_PENDING">Pending</option>
                    <option value="ST_RUNNING">Running</option>
                    <option value="ST_SUCCESS">Success</option>
                    <option value="ST_ERROR">Error</option>
                </select>
            </span>
        </div>
    }
}

export default JobsFilterComponent;