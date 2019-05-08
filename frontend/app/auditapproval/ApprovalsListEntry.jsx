import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import axios from 'axios';
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import ApprovalStateIcon from "./ApprovalStateIcon.jsx";
import BytesFormatter from "../common/BytesFormatter.jsx";

class ApprovalsListEntry extends React.Component {
    static propTypes = {
        data: PropTypes.object.isRequired,
        totalSize: PropTypes.number.isRequired,
        approvalDoneCb: PropTypes.func.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            expanded: false,
            adminsReason: "",
            loading: false,
            lastError: null,
            approvalErrorLabel: ""
        };

        this.doApproval = this.doApproval.bind(this);
        this.doRejection = this.doRejection.bind(this);
    }

    isResolved(){
        return (this.props.data.approvalStatus==="Allowed"|| this.props.data.approvalStatus==="Rejected")
    }

    doApproval() {
        const approvalRequest = {
            bulkId: this.props.data.bulkId,
            approval: "Allowed",
            notes: this.state.adminsReason
        };

        this.sendApprovalRequest(approvalRequest);
    }

    doRejection(){
        const approvalRequest = {
            bulkId: this.props.data.bulkId,
            approval: "Rejected",
            notes: this.state.adminsReason
        };

        this.sendApprovalRequest(approvalRequest);
    }

    sendApprovalRequest(approvalRequest){
        if(this.state.adminsReason.length<10){
            this.setState({approvalErrorLabel: "You must specify a reason for approval or rejection, longer than 10 characters"});
        } else if(this.state.loading) {
            this.setState({approvalErrorLabel: "Operation already in progress"});
        } else {
            this.setState({
                loading: true,
                lastError: null,
                approvalErrorLabel: "",
            }, () => axios.post("/api/audit/manualApproval", approvalRequest).then(response => {
                this.props.approvalDoneCb(this.props.data.bulkId)
            }).catch(err => {
                console.error(err);
                this.setState({loading: false, lastError: err});
            }))
        }
    }

    contextStringFor(approvalStatus, maybeApprover){
        switch(approvalStatus){
            case "Pending": return "this requires administrator approval";
            case "Allowed": return "this has been approved by " + maybeApprover;
            case "Rejected": return "this has been denied by " + maybeApprover;
            case "Queried": return "this has been queried by " + maybeApprover;
        }
    }
    render(){
        return <li className="approvals-list-entry" key={this.props.data.bulkId}>
            <ApprovalStateIcon approvalStatus={this.props.data.approvalStatus}/>
            <span>
            {this.props.data.requestedBy} wants to restore <BytesFormatter value={this.props.totalSize}/> of data, {this.contextStringFor(this.props.data.approvalStatus, this.props.data.approval ? this.props.data.approval.approver : null)}
            </span>
            <span style={{ float: "right" }}><a className="clickable" onClick={evt=>this.setState({expanded: !this.state.expanded})}>{this.state.expanded ? "hide" : "show"} details</a></span>

            <span style={{float: "right"}}>
                <LoadingThrobber show={this.state.loading} small={true}/>
                <ErrorViewComponent error={this.state.lastError}/>
            </span>
            <div style={{display: this.state.expanded ? "block" : "none", marginLeft: "1em"}}>
                <p>{this.props.data.requestedBy} lightboxed <BytesFormatter value={this.props.totalSize}/> of data from <span className="code">{this.props.data.basePath}</span>
                <TimestampFormatter relative={true} value={this.props.data.requestedAt}/></p>

                <p>Reason given: {this.props.data.reasonGiven}</p>
                /* show any warnings here */
                <span style={{display: this.isResolved() ? "none" : "block"}}>
                    <span style={{float:"right"}}>
                        <a className="clickable" style={{marginRight: "1em"}} onClick={this.doApproval}>Approve</a>
                        <a className="clickable" style={{marginRight: "1em"}} onClick={this.doRejection}>Reject</a>
                    </span>

                    <label htmlFor="admins-reason" style={{verticalAlign: "top", marginRight: "1em"}}>Reason:</label>
                    <textarea id="admins-reason" value={this.state.adminsReason} onChange={evt=>this.setState({adminsReason: evt.target.value})}/>
                    <p className="error-text">{this.state.approvalErrorLabel}</p>
                </span>
                <span style={{display: this.props.data.approval ? "block" : "none"}}>
                    <p>Administrator said: {this.props.data.approval ? this.props.data.approval.comment : "nothing"}</p>
                </span>
            </div>
        </li>
    }
}

export default  ApprovalsListEntry;
