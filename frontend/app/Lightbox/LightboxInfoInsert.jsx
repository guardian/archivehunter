import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import RestoreStatusComponent from "./RestoreStatusComponent.jsx";

class LightboxInfoInsert extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired,
        extraInfo: PropTypes.string
    };

    render(){
        if(!this.props.entry) return <div/>;

        return <div className="centered">
            <span style={{display: "block"}}>
                Added to lightbox <TimestampFormatter relative={true} value={this.props.entry.addedAt}/>
            </span>
            <p className="centered">Archive availability</p>
            <RestoreStatusComponent
                status={this.props.entry.restoreStatus}
                startTime={this.props.entry.restoreStarted}
                completed={this.props.entry.restoreCompleted}
                expires={this.props.entry.availableUntil}
                hidden={this.props.entry.restoreStatus==="RS_UNNEEDED"}/>
            {
                this.props.extraInfo ? <p className="centered">{this.props.extraInfo}</p> : ""
            }
        </div>
    }
}

export default LightboxInfoInsert;