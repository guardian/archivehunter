import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';

class TimestampFormatter extends React.Component {
    static propTypes = {
        relative: PropTypes.bool.isRequired,
        value: PropTypes.string.isRequired,
        formatString: PropTypes.string
    };

    render(){
        const formatToUse = this.props.formatString ? this.props.formatString : "";
        const m = moment(this.props.value);

        const out = this.props.relative ? m.fromNow(false) : m.format(formatToUse);
        return <span className="timestamp">{out}</span>
    }
}

export default TimestampFormatter;