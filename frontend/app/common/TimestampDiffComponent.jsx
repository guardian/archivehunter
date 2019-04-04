import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';

class TimestampDiffComponent extends React.Component {
    static propTypes = {
        startTime: PropTypes.string.isRequired,
        endTime: PropTypes.string.isRequired,
        formatString: PropTypes.string
    };

    render(){
        const formatToUse = this.props.formatString ? this.props.formatString : "";
        const startMoment = moment(this.props.startTime);
        const endMoment = moment(this.props.endTime);

        const out = endMoment.from(startMoment);
        return <span className="timestamp">{out}</span>
    }
}

export default TimestampDiffComponent;