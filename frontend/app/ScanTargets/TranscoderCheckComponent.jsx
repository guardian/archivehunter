import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter";
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'

class TranscoderCheckComponent extends React.Component {
    static propTypes = {
        status: PropTypes.string.isRequired,
        checkedAt: PropTypes.string.isRequired,
        log: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            showingLog: false
        }
    }

    renderStatusIcon(){
        switch(this.props.status){
            case "ST_SUCCESS":
                return <FontAwesomeIcon icon="check-circle" style={{color: "green", marginRight: "0.2em"}}/>;
            case "ST_ERROR":
                return <FontAwesomeIcon icon="times-circle" style={{color: "red", marginRight: "0.2em"}}/>;
            default:
                return <FontAwesomeIcon icon="stroopwafel" style={{color: "yellow", marginRight: "0.2em"}}/>;
        }
    }

    render(){
        return <span>
            {
                this.renderStatusIcon()
            }
            <TimestampFormatter relative={true} value={this.props.checkedAt}/><br/>
            <pre style={{display: this.state.showingLog ? "block" : "none" }} className="inline-log">{this.props.log}</pre>
            <a onClick={()=>this.setState({showingLog: !this.state.showingLog})} style={{cursor: "pointer"}} className="information">
                {this.state.showingLog ? "hide " : "show "} details</a>
        </span>;
    }
}

export default TranscoderCheckComponent;