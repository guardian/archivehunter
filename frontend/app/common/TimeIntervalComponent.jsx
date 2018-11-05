import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';
import 'moment-duration-format';

class TimeIntervalComponent extends React.Component {
    static propTypes = {
        editable: PropTypes.bool.isRequired,
        value: PropTypes.number.isRequired
    };

    render(){
        const d = moment.duration(this.props.value,"seconds");
        let fmtStringParts = [];
        if(d.hours()>0) fmtStringParts = fmtStringParts.concat(["h [hours]"]);
        if(d.minutes()>0) fmtStringParts = fmtStringParts.concat(["m [minutes]"]);
        if(d.seconds()>0) fmtStringParts = fmtStringParts.concat(["s [seconds]"]);
        const formatString = fmtStringParts.join(", ");

        return <span className="duration">{d.format(formatString)}</span>
    }
}

export default TimeIntervalComponent;