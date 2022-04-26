import React from 'react';
import PropTypes from 'prop-types';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import TimestampFormatter from "../common/TimestampFormatter";
import axios from 'axios';
import {
    AccessTime,
    CheckCircle,
    DirectionsRun,
    RemoveShoppingCart,
    RemoveShoppingCartRounded,
    WarningRounded
} from "@material-ui/icons";
import {Tooltip} from "@material-ui/core";

class RestoreStatusComponent extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired,
        startTime: PropTypes.string.isRequired,
        completed: PropTypes.string.isRequired,
        expires: PropTypes.string.isRequired,
        hidden: PropTypes.bool
    };

    /**
     *   val RS_UNNEEDED,RS_ALREADY,RS_PENDING,RS_UNDERWAY,RS_SUCCESS,RS_ERROR,RS_EXPIRED=Value
     */
    statusIcon(){
        switch(this.props.status){
            case "RS_PENDING":
                return <Tooltip title="Pending"><AccessTime/></Tooltip>;
            case "RS_UNDERWAY":
                return <Tooltip title="Running"><DirectionsRun style={{color: "blue"}}/></Tooltip>;
            case "RS_SUCCESS":
            case "RS_ALREADY":
                return <Tooltip title="Available"><CheckCircle style={{color:"green"}}/></Tooltip>;
            case "RS_ERROR":
                return <Tooltip title="Error"><WarningRounded style={{color:"red"}}/></Tooltip>;
            case "RS_EXPIRED":
                return <Tooltip title="Expired"><RemoveShoppingCartRounded/></Tooltip>;
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
            default:
                return <span/>;
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