import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import axios from 'axios';

class RestoreStatusComponent extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired,
        startTime: PropTypes.string.isRequired,
        completed: PropTypes.string.isRequired,
        expires: PropTypes.string.isRequired,
        hidden: PropTypes.bool
    };

    /**
     *   val RS_UNNEEDED,RS_ALREADY,RS_PENDING,RS_UNDERWAY,RS_SUCCESS,RS_ERROR=Value
     */
    statusIcon(){
        switch(this.props.status){
            case "RS_PENDING":
                return <span data-tip="Pending"><FontAwesomeIcon size="1.5x" icon="clock"/></span>;
            case "RS_UNDERWAY":
                return <span data-tip="Running" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="running" style={{color: "blue"}}/></span>;
            case "RS_SUCCESS":
            case "RS_ALREADY":
                return <span data-tip="Success" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="check-circle" style={{color:"green"}}/></span>;
            case "RS_ERROR":
                return <span data-tip="Error" data-for="jobslist-tooltip"><FontAwesomeIcon size="1.5x" icon="exclamation-triangle" style={{color:"red"}}/></span>;
            default:
                return <span data-tip={this.props.status}>{this.props.status}</span>;
        }
    }

    timeDisplay(){
        switch(this.props.status){
            case "RS_PENDING":
            case "RS_UNDERWAY":
                return <TimestampFormatter relative={true} value={this.props.startTime}/>;
            case "RS_SUCCESS":
            case "RS_ALREADY":
                return <TimestampFormatter relative={true} value={this.props.completed}/>;
            case "RS_ERROR":
                return <TimestampFormatter relative={true} value={this.props.completed ? this.props.completed : this.props.startTime}/>;
        }
    }

    isCompleted(){
        return this.props.status==="RS_SUCCESS" || this.props.status==="RS_ALREADY"
    }

    render(){
        return <div style={{display: this.props.hidden ? "none" : "block"}} className="centered">
            <p>{this.statusIcon()} since {this.timeDisplay()}</p>
        </div>
    }
}

export default RestoreStatusComponent;