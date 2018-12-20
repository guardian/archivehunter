import React from 'react';
import PropTypes from 'prop-types';

class LoadingThrobber extends React.Component {
    static propTypes = {
        show: PropTypes.bool.isRequired,
        small: PropTypes.bool,
        large: PropTypes.bool,
        caption: PropTypes.string
    };

    render(){
        const path = this.props.small ? "/assets/images/Spinner-1s-44px.svg" : "/assets/images/Spinner-1s-200px.gif";

        return <span style={{display: this.props.show ? "block" : "none"}}>
            <img src={path}/><p style={{verticalAlign: "middle"}}>{this.props.caption ? this.props.caption : ""}</p>
        </span>
    }
}

export default LoadingThrobber;