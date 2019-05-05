import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter";

class ApprovalsListEntry extends React.Component {
    static propTypes: {
        data: PropTypes.object.isRequired
    };

    constructor(props){
        super(props);
        this.state = {
            expanded: false,

        }
    }

    isResolved(){
        return !(this.props.data.approvalStatus==="Allowed"|| this.props.data.approvalStatus==="Rejected")
    }

    render(){
        return <li className="approvals-list-entry">
            <span>
            /* insert appropriate icon */
            {this.props.data.requestedBy} wants to restore ?? of data, {this.contextStringFor(this.props.data.approvalStatus)}
            </span>
            <span><a href="#" onClick={this.setState({expanded: !this.state.expanded})}>{this.state.expanded ? "hide" : "show"} details</a></span>

            <div style={{display: this.state.expanded ? "block" : "none", marginLeft: "1em"}}>
                <p>{this.props.data.requestedBy} lightboxed ?? of data from {this.props.data.basepath}
                <TimestampFormatter relative={true} value={this.props.data.requestedAt}/></p>

                <p>Reason given: {this.props.data.reasonGiven}</p>
                /* show any warnings here */
                <span style={{display: this.isResolved() ? "none" : "inline" }}>
                </span>
            </div>
        </li>
    }
}

export default  ApprovalsListEntry;
