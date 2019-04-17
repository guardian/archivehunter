import React from 'react';
import PropTypes from 'prop-types';

class LoadingThrobber extends React.Component {
    static propTypes = {
        show: PropTypes.bool.isRequired,
        small: PropTypes.bool,
        large: PropTypes.bool,
        caption: PropTypes.string,
        inline: PropTypes.bool
    };

    render(){
        const path = this.props.small ? "/assets/images/Spinner-1s-44px.svg" : "/assets/images/Spinner-1s-200px.gif";

        const showType = this.props.inline ? "inline": "block";

        return <span style={{display: this.props.show ? showType : "none"}}>
            <img src={path} style={{verticalAlign: "middle"}}/><p style={{verticalAlign: "middle", display: "inline"}}>{this.props.caption ? this.props.caption : ""}</p>
        </span>
    }
}

export default LoadingThrobber;