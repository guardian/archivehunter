import React from 'react';
import PropTypes from 'prop-types';
import TimestampFormatter from "../common/TimestampFormatter.jsx";
import RestoreStatusComponent from "./RestoreStatusComponent.jsx";

class LightboxInfoInsert extends React.Component {
    static propTypes = {
        entry: PropTypes.object.isRequired
    };

    render(){
        if(!this.props.entry) return <div/>;

        return <div className="centered">
            <span style={{display: "block"}}>
                Added to lightbox <TimestampFormatter relative={true} value={this.props.entry.addedAt}/>
            </span>
            <RestoreStatusComponent
                status={this.props.entry.restoreStatus}
                startTime={this.props.entry.restoreStarted}
                completed={this.props.entry.restoreCompleted}
                expires={this.props.entry.availableUntil}
                hidden={this.props.entry.restoreStatus==="RS_UNNEEDED"}/>
        </div>
    }
}

export default LightboxInfoInsert;