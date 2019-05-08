import React from 'react';
import PropTypes from 'prop-types';
import {FontAwesomeIcon} from "@fortawesome/react-fontawesome";

class ApprovalStateIcon extends React.Component {
    static propTypes = {
        approvalStatus: PropTypes.string
    };

    constructor(props){
        super(props);
    }

    render(){
        //these values correspond to the enum in ApprovalStatus.scala
        switch(this.props.approvalStatus){
            case "Pending":
                return <span className="approval-state-icon" data-tip="Pending" data-for="approvalstatus-tooltip"><FontAwesomeIcon size="1.5x" icon="exclamation-triangle" style={{color:"red"}}/></span>;
            case "Queried":
                return <span className="approval-state-icon" data-tip="Queried" data-for="approvalstatus-tooltip"><FontAwesomeIcon size="1.5x" icon="question-circle" style={{color: "blue"}}/></span>;
            case "Allowed":
                return <span className="approval-state-icon" data-tip="Allowed" data-for="approvalstatus-tooltip"><FontAwesomeIcon size="1.5x" icon="check-circle" style={{color:"green"}}/></span>;
            case "Rejected":
                return <span className="approval-state-icon" data-tip="Rejected" data-for="approvalstatus-tooltip"><FontAwesomeIcon size="1.5x" icon="times-circle" style={{color:"yellow"}}/></span>;
            default:
                return <span className="approval-state-icon" data-tip={this.props.status}>{this.props.status}</span>;
        }
    }
}

export default ApprovalStateIcon;