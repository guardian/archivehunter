import React from 'react';
import axios from 'axios';
import PropTypes from 'prop-types';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import ApprovalsListEntry from "./ApprovalsListEntry.jsx";

class ApprovalsList extends React.Component {
    constructor(props){
        super(props);
        this.state = {
            loading: false,
            lastError: null,
            selectedStatus: "Pending",
            approvalsList: [],
            totalSizes: []
        };

        this.approvalDone = this.approvalDone.bind(this);

    }

    componentWillMount() {
        this.refreshData();
    }

    refreshData(){
        this.setState({loading: true, lastError: null}, ()=>axios.get("/api/audit/approvals/byStatus?statusString=" + this.state.selectedStatus).then(response=>{
            this.setState({loading: false, approvalsList: response.data.entries, totalSizes: response.data.totalSizes});
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }

    approvalDone(){
        this.refreshData();
    }

    render(){
        if(this.state.lastError) return <div className="chart-container"><ErrorViewComponent error={this.state.lastError}/></div>;
        return <div className="chart-container" style={{paddingLeft: "2em"}}>
            <h3>Actions</h3>
            <span className="approvals-list-typeselector">
                <label htmlFor="approvals-list-pending">Pending</label>
                <input className="radio-spacing" id="approvals-list-pending" type="radio" checked={this.state.selectedStatus==="Pending"} onChange={evt=>this.setState({selectedStatus: "Pending"},this.refreshData)}/>
                <label htmlFor="approvals-list-approved">Approved</label>
                <input className="radio-spacing" id="approvals-list-approved" type="radio" checked={this.state.selectedStatus==="Allowed"} onChange={evt=>this.setState({selectedStatus: "Allowed"},this.refreshData)}/>
                <label htmlFor="approvals-list-rejected">Rejected</label>
                <input className="radio-spacing" id="approvals-list-rejected" type="radio" checked={this.state.selectedStatus==="Rejected"} onChange={evt=>this.setState({selectedStatus: "Rejected"},this.refreshData)}/>
                <LoadingThrobber show={this.state.loading} small={true} inline={true}/>
            </span>
            <ul className="approvals-list">
                {this.state.approvalsList.length===0 ? <p className="information">There are no {this.state.selectedStatus.toLowerCase()} actions to list</p> : ""}
                {this.state.approvalsList.map((listEntry, idx)=><ApprovalsListEntry data={listEntry} totalSize={this.state.totalSizes[idx]} approvalDoneCb={this.approvalDone}/>)}
            </ul>
        </div>
    }
}

export default ApprovalsList;
